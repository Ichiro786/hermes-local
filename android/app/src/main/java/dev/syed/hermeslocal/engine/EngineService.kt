package dev.syed.hermeslocal.engine

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.syed.hermeslocal.MainActivity
import dev.syed.hermeslocal.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that owns the Hermes process (proot -> hermes-local-start -> hermes dashboard).
 * specialUse type (no daily cap), partial wakelock while running, survives the app being swiped away
 * (stopWithTask=false), START_STICKY: restarted by the system, it brings Hermes back if the user wanted it on.
 */
class EngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var stopping = false
    private var restarts = 0
    private lateinit var paths: EnginePaths
    private lateinit var settings: Settings

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        paths = EnginePaths(this)
        settings = Settings.get(this)
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: a started FGS must call startForeground within seconds.
        goForeground(notification("Starting Hermes…", 0L))
        when (intent?.action ?: ACTION_START) { // null intent = sticky restart by the system
            ACTION_STOP -> stopEngine(userRequested = true)
            ACTION_RESTART -> scope.launch {
                EngineState.set { it.copy(phase = Phase.Starting, runningSince = 0L) }
                killProcess(); runJob?.join(); runJob = null; restarts = 0; startEngine()
            }
            else -> if (intent == null && !settings.wantRunning) stopSelfClean() else startEngine()
        }
        return START_STICKY
    }

    private fun goForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun startEngine() {
        if (runJob?.isActive == true) return
        stopping = false
        settings.wantRunning = true
        val s = settings.value
        ProotCommand.preflight(paths)?.let { fail(it); return }
        if (!s.passwordSet) { fail("Set a dashboard password first"); return }
        EngineState.set { it.copy(phase = Phase.Starting, port = s.port, lan = s.lanAccess, message = null, runningSince = 0L) }
        acquireLocks(s)
        runJob = scope.launch { runOnce(s) }
    }

    private suspend fun runOnce(s: SettingsSnapshot) {
        writeSettingsEnv(s)
        rotateLog()
        EngineState.clearLines()
        log("--- start ${stamp()} port=${s.port} lan=${s.lanAccess} api=${s.apiServer}")
        val p = try {
            ProotCommand.processBuilder(paths, listOf("/bin/bash", "/opt/hermes-local/bin/hermes-local-start")).start()
        } catch (e: Exception) {
            fail("Could not start proot: ${e.message}"); return
        }
        process = p
        p.outputStream.close()
        val health = scope.launch { healthLoop(s.port) }
        var lastLine = ""
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                log(line)
                if (line.isNotBlank()) lastLine = line
                when {
                    line.startsWith("HERMES_LOCAL_READY") -> markRunning(s.port)
                    line.startsWith("HERMES_LOCAL_FAILED") -> EngineState.set { it.copy(message = line.removePrefix("HERMES_LOCAL_FAILED").trim()) }
                }
            }
        } catch (_: Exception) {
        }
        val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
        health.cancel()
        process = null
        log("--- exited code=$code ${stamp()}")
        if (stopping) return
        // Unexpected exit: report, then retry a few times when the user wants it on.
        val why = EngineState.status.value.message ?: lastLine.take(200).ifBlank { "Hermes exited (code $code)" }
        if (settings.value.autoRestart && settings.wantRunning && restarts < 3) {
            restarts++
            EngineState.set { it.copy(phase = Phase.Starting, message = "Restarting after a crash ($restarts/3): $why") }
            delay(3000L * restarts)
            if (!stopping) { runJob = null; startEngine() }
        } else {
            fail(why)
        }
    }

    private suspend fun healthLoop(port: Int) {
        var misses = 0
        while (scope.isActive) {
            delay(if (EngineState.status.value.phase == Phase.Running) 5000 else 1000)
            val ok = probe(port)
            if (ok) { misses = 0; if (EngineState.status.value.phase == Phase.Starting) markRunning(port) }
            else if (EngineState.status.value.phase == Phase.Running && ++misses >= 3) {
                EngineState.set { it.copy(message = "Dashboard is not answering") }
            }
        }
    }

    private fun markRunning(port: Int) {
        if (EngineState.status.value.phase == Phase.Running) return
        restarts = 0
        val since = System.currentTimeMillis()
        EngineState.set { it.copy(phase = Phase.Running, runningSince = since, port = port, message = null) }
        updateNotification(notification("Hermes is running · 127.0.0.1:$port", since))
    }

    private fun stopEngine(userRequested: Boolean) {
        if (userRequested) settings.wantRunning = false
        stopping = true
        EngineState.set { it.copy(phase = Phase.Stopping) }
        updateNotification(notification("Stopping Hermes…", 0L))
        scope.launch {
            killProcess()
            runJob?.cancel()
            runJob = null
            EngineState.set { it.copy(phase = Phase.Stopped, runningSince = 0L, message = null) }
            stopSelfClean()
        }
    }

    private fun killProcess() {
        val p = process ?: return
        stopping = true
        p.terminate(8000) // SIGTERM lets Hermes flush sessions; proot --kill-on-exit takes the guests down
        process = null
    }

    private fun fail(message: String) {
        settings.wantRunning = false
        EngineState.set { it.copy(phase = Phase.Failed, message = message, runningSince = 0L) }
        log("FAILED: $message")
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSelfClean() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        process?.terminate(3000)
        releaseLocks()
        scope.cancel()
        val ph = EngineState.status.value.phase
        if (ph == Phase.Running || ph == Phase.Starting || ph == Phase.Stopping) EngineState.set { it.copy(phase = Phase.Stopped, runningSince = 0L) }
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks(s: SettingsSnapshot) {
        if (s.keepAwake && wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesLocal:engine").apply { setReferenceCounted(false); acquire() }
        }
        if (s.lanAccess && wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "HermesLocal:lan").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
    }

    private fun writeSettingsEnv(s: SettingsSnapshot) {
        paths.guestState.mkdirs()
        paths.settingsEnv.writeText(
            "HL_HOST=${if (s.lanAccess) "0.0.0.0" else "127.0.0.1"}\n" +
                "HL_PORT=${s.port}\nHL_API_SERVER=${if (s.apiServer) 1 else 0}\nHL_API_PORT=${s.apiPort}\n"
        )
    }

    private val logLock = Any()
    private fun log(line: String) {
        EngineState.appendLine(line)
        synchronized(logLock) {
            try { FileWriter(paths.engineLog, true).use { it.write(line); it.write("\n") } } catch (_: Exception) {}
        }
    }

    private fun rotateLog() {
        paths.ensureDirs()
        val f = paths.engineLog
        if (f.length() > 2_000_000) { paths.engineLogOld.delete(); f.renameTo(paths.engineLogOld) }
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun notification(text: String, since: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hermes Local")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .apply { if (since > 0) setWhen(since).setUsesChronometer(true).setShowWhen(true) }
            .build()
    }

    private fun updateNotification(n: Notification) {
        if (stopping && EngineState.status.value.phase == Phase.Stopped) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
    }

    companion object {
        const val ACTION_START = "dev.syed.hermeslocal.START"
        const val ACTION_STOP = "dev.syed.hermeslocal.STOP"
        const val ACTION_RESTART = "dev.syed.hermeslocal.RESTART"
        private const val CHANNEL_ID = "engine"
        private const val NOTIFICATION_ID = 7

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hermes server", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Hermes runs on this phone"
                    setShowBadge(false)
                }
            )
        }

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java).setAction(ACTION_START))

        fun stop(context: Context) {
            if (!EngineState.status.value.isOn && EngineState.status.value.phase != Phase.Stopping) {
                Settings.get(context).wantRunning = false; return
            }
            ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java).setAction(ACTION_STOP))
        }

        fun restart(context: Context) {
            if (EngineState.status.value.isOn)
                ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java).setAction(ACTION_RESTART))
        }

        /** Probe the dashboard: any HTTP answer from the auth bootstrap route means it is up. */
        fun probe(port: Int): Boolean = try {
            val c = URL("http://127.0.0.1:$port/api/auth/providers").openConnection() as HttpURLConnection
            c.connectTimeout = 1500; c.readTimeout = 2000
            val code = c.responseCode
            c.disconnect()
            code in 200..499
        } catch (_: Exception) {
            false
        }

        fun logFile(context: Context): File = EnginePaths(context).engineLog
    }
}
