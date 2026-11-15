package run.hermes.empryo.engine

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import run.hermes.empryo.MainActivity
import run.hermes.empryo.R
import java.io.FileWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service (specialUse) that holds `empryo acp` under proot; the app's chat talks to it over
 * stdio. With "Pair official Empryo app" (and LAN access) on, it also runs `empryo phone-service
 * --login-item --via wifi` (never `empryo phone on`, which needs systemd and copies 368 MB).
 * Partial wake lock only while a turn runs (+45 s), or all the time with "Keep awake".
 */
class EngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acp: Process? = null
    @Volatile private var bridge: Process? = null
    @Volatile private var pairHelper: Process? = null
    @Volatile private var bridgeStopRequested = false
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mcastLock: WifiManager.MulticastLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val releaseTail = Runnable { if (!settings.value.keepAwake) wakeLock?.let { if (it.isHeld) it.release() } }
    @Volatile private var stopping = false
    private lateinit var paths: Paths
    private lateinit var settings: Settings
    private val stderrTail = ArrayDeque<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        paths = Paths(this)
        settings = Settings.get(this)
        createChannel(this)
        Chat.onTurn = { active -> main.post { onTurn(active) } }
        Pairing.onRequest = { r -> pairNotification(r) }
        // Wi-Fi <-> mobile data changes the DNS servers: rewrite the rootfs resolv.conf (glibc re-reads it).
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback) }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { runCatching { GuestSetup.apply(paths) } }
    }

    /** "Allow X to pair?" as a heads-up notification; tapping opens the Pair screen (Allow / Deny live there). */
    private fun pairNotification(r: PairRequest?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (r == null) { nm.cancel(PAIR_NOTIFICATION_ID); return }
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_SCREEN, "Pair"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(PAIR_NOTIFICATION_ID, NotificationCompat.Builder(this, PAIR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${r.name} wants to pair")
            .setContentText("Allow only if it shows ${r.fingerprint}. Tap to answer.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val st = EngineState.status.value
        goForeground(if (st.phase == Phase.Running) notification("Empryo is running", st.runningSince) else notification("Starting Empryo…", 0L))
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> stopEngine()
            ACTION_BRIDGE -> if (EngineState.status.value.isOn) ensureBridge() else startEngine()
            ACTION_BRIDGE_STOP -> stopBridge()
            else -> if (intent == null && !wantRunning(this)) stopSelfClean() else startEngine()
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
        setWantRunning(this, true)
        Proot.preflight(paths)?.let { fail(it); return }
        EngineState.set { it.copy(phase = Phase.Starting, message = null, runningSince = 0L, bridge = null) }
        if (settings.value.keepAwake) holdWakeLock()
        runJob = scope.launch { runAcp() }
    }

    private suspend fun runAcp() {
        val s = settings.value
        paths.ensureDirs()
        runCatching { EngineConfig.apply(paths, s) }.onFailure { log("config.json not updated: ${it.message}") }
        log("--- start ${stamp()} acp, bridge=${s.pairOfficial} lan=${s.lanAccess} port=${s.bridgePort} dns=${GuestSetup.networkDns(this)}")
        val p = try {
            Proot.processBuilder(paths, listOf(Paths.EMPRYO, "acp"), splitStderr = true).start()
        } catch (e: Exception) {
            fail("Could not start proot: ${e.message}"); return
        }
        acp = p
        acpPid = p.pidCompat()
        val client = AcpClient(p.outputStream)
        Thread({ p.errorStream.bufferedReader().forEachLine { l -> synchronized(stderrTail) { stderrTail.addLast(l); while (stderrTail.size > 60) stderrTail.removeFirst() }; log("err: $l") } }, "acp-err").apply { isDaemon = true }.start()
        Thread({
            try { p.inputStream.bufferedReader().forEachLine { l -> if (!client.onLine(l)) log("out: $l") } } catch (_: Exception) {}
            client.failAll("Engine stopped")
        }, "acp-out").apply { isDaemon = true }.start()
        try {
            val info = client.initialize()
            val ver = info.optJSONObject("agentInfo")?.optString("version") ?: "?"
            Chat.client = client
            val since = System.currentTimeMillis()
            EngineState.set { it.copy(phase = Phase.Running, runningSince = since, message = null) }
            updateNotification(notification("Empryo $ver is running", since))
            log("acp ready: empryo $ver")
        } catch (e: Exception) {
            log("acp initialize failed: ${e.message}")
            p.terminate(3000) // the exit below reports stderr
        }
        if (s.pairOfficial) scope.launch { runBridge(s.bridgePort) }
        val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
        Chat.client = null
        acp = null; acpPid = null
        log("--- acp exited code=$code ${stamp()}")
        if (!stopping) fail("Engine exited (code $code): " + synchronized(stderrTail) { stderrTail.takeLast(6).joinToString("\n") }.ifBlank { "no output" })
    }

    /**
     * The official Empryo app's bridge: `empryo phone-service --login-item` (never `empryo phone on`, which needs
     * systemd and copies 368 MB), plus the pairing helper attached to its control socket (see [Pairing]).
     * phone-service hard-links its control socket; if proot's link2symlink emulation breaks that ("reclaimed-stale" /
     * "lost-link" on the x86_64 emulator in test1) it retries once with real hard links and remembers what worked.
     */
    private suspend fun runBridge(port: Int) {
        if (bridge != null) return
        acquireMulticast()
        val via = if (settings.value.lanAccess) "auto" else "quick"
        EngineState.set { it.copy(bridge = "Starting on :$port") }
        Pairing.setState("starting")
        var l2s = prefs(this).getBoolean(PREF_BRIDGE_L2S, true)
        var code = -1
        var last = ""
        var attempt = 0
        bridgeStopRequested = false
        while (true) {
            attempt++
            val p = try {
                Proot.processBuilder(paths, listOf(Paths.EMPRYO, "phone-service", "--cwd", Paths.WORKSPACE, "--login-item", "--via", via), link2symlink = l2s).start()
            } catch (e: Exception) {
                EngineState.set { it.copy(bridge = "Could not start: ${e.message}") }; Pairing.setState("failed", e.message); return
            }
            bridge = p
            bridgePid = p.pidCompat()
            p.outputStream.close()
            log("bridge: phone-service --via $via (link2symlink=$l2s)")
            val t0 = System.currentTimeMillis()
            val tail = ArrayDeque<String>()
            Thread({ p.inputStream.bufferedReader().forEachLine { l -> synchronized(tail) { tail.addLast(l); if (tail.size > 20) tail.removeFirst() }; log("bridge: $l") } }, "bridge").apply { isDaemon = true }.start()
            val helper = scope.launch { runPairHelper(p) }
            scope.launch {
                repeat(30) {
                    if (bridge !== p) return@launch
                    val open = (port..port + 3).firstOrNull { portOpen(it) }
                    if (open != null) { EngineState.set { it.copy(bridge = "Running on :$open (${if (via == "quick") "quick link" else "Wi-Fi + quick link"})") }; return@launch }
                    delay(1000)
                }
            }
            code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
            helper.cancel()
            last = synchronized(tail) { tail.lastOrNull() ?: "no output" }
            val linkTrouble = listOf("reclaim", "lost-link", "hardlink", "is taken").any { last.contains(it) }
            if (!stopping && bridge === p && attempt == 1 && linkTrouble && System.currentTimeMillis() - t0 < 30_000) {
                l2s = !l2s
                log("bridge: exited ($code) \"$last\"; retrying with link2symlink=$l2s")
                continue
            }
            if (!stopping && bridge === p && attempt == 2 && code != 0) l2s = !l2s // neither worked: keep the default
            break
        }
        prefs(this).edit().putBoolean(PREF_BRIDGE_L2S, l2s).apply()
        bridge = null; bridgePid = null
        releaseMulticast()
        Pairing.setState("off")
        if (!stopping && !bridgeStopRequested) EngineState.set { it.copy(bridge = "Exited ($code): $last") }
    }

    /** Keeps the pairing helper attached while phone-service runs (it exits until service.json and the socket exist). */
    private suspend fun runPairHelper(service: Process) {
        var failures = 0
        while (bridge === service && failures < 40) {
            delay(if (failures == 0) 1500 else 2000)
            val h = try { Pairing.startHelper(paths) } catch (e: Exception) { log("pair helper: ${e.message}"); failures++; continue }
            pairHelper = h
            Pairing.attached(h.outputStream)
            try {
                h.inputStream.bufferedReader().forEachLine { l -> Pairing.onLine(l)?.let { log(it) } }
            } catch (_: Exception) {}
            Pairing.attached(null)
            val rc = runCatching { h.waitFor() }.getOrDefault(-1)
            pairHelper = null
            if (rc != 0) failures++ else failures = 0
        }
        if (bridge === service) Pairing.setState("failed", "Could not attach to phone-service (see engine log)")
    }

    /** UI asked for pairing while the engine runs. */
    private fun ensureBridge() {
        if (bridge != null || EngineState.status.value.phase != Phase.Running) return
        scope.launch { runBridge(settings.value.bridgePort) }
    }

    private fun stopBridge() {
        bridgeStopRequested = true
        scope.launch { pairHelper?.terminate(1000); bridge?.let { b -> bridge = null; b.terminate(4000) }; EngineState.set { it.copy(bridge = null) }; Pairing.setState("off") }
    }

    private fun stopEngine() {
        setWantRunning(this, false)
        stopping = true
        EngineState.set { it.copy(phase = Phase.Stopping) }
        updateNotification(notification("Stopping Empryo…", 0L))
        scope.launch {
            Chat.client = null
            pairHelper?.terminate(1000)
            bridge?.terminate(4000); bridge = null
            acp?.terminate(6000); acp = null
            runJob?.cancel(); runJob = null
            EngineState.set { it.copy(phase = Phase.Stopped, runningSince = 0L, message = null, bridge = null, turnActive = false) }
            stopSelfClean()
        }
    }

    private fun fail(message: String) {
        setWantRunning(this, false)
        Chat.client = null
        bridge?.terminate(2000); bridge = null
        EngineState.set { it.copy(phase = Phase.Failed, message = message, runningSince = 0L, bridge = null, turnActive = false) }
        log("FAILED: $message")
        stopSelfClean()
    }

    private fun stopSelfClean() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        Chat.client = null
        bridge?.terminate(2000)
        acp?.terminate(3000)
        releaseLocks()
        scope.cancel()
        val ph = EngineState.status.value.phase
        if (ph == Phase.Running || ph == Phase.Starting || ph == Phase.Stopping) EngineState.set { it.copy(phase = Phase.Stopped, runningSince = 0L) }
        super.onDestroy()
    }

    private fun onTurn(active: Boolean) {
        EngineState.set { it.copy(turnActive = active) }
        main.removeCallbacks(releaseTail)
        if (active) holdWakeLock() else main.postDelayed(releaseTail, TURN_TAIL_MS)
    }

    @SuppressLint("WakelockTimeout")
    private fun holdWakeLock() {
        val wl = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EmpryoLocal:turn").apply { setReferenceCounted(false) }.also { wakeLock = it }
        if (!wl.isHeld) wl.acquire()
    }

    private fun acquireMulticast() {
        if (mcastLock != null) return
        mcastLock = runCatching {
            (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createMulticastLock("EmpryoLocal:mdns").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }

    private fun releaseMulticast() { mcastLock?.let { if (it.isHeld) it.release() }; mcastLock = null }

    private fun releaseLocks() {
        main.removeCallbacks(releaseTail)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        releaseMulticast()
    }

    private val logLock = Any()
    private fun log(line: String) = synchronized(logLock) {
        try {
            val f = paths.engineLog
            if (f.length() > 2_000_000) f.delete()
            FileWriter(f, true).use { it.write(line); it.write("\n") }
        } catch (_: Exception) {}
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
            .setContentTitle("Empryo Local")
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
        if (stopping) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
    }

    companion object {
        const val ACTION_START = "run.hermes.empryo.START"
        const val ACTION_STOP = "run.hermes.empryo.STOP"
        const val ACTION_BRIDGE = "run.hermes.empryo.BRIDGE"
        const val ACTION_BRIDGE_STOP = "run.hermes.empryo.BRIDGE_STOP"
        private const val PREF_BRIDGE_L2S = "bridge_link2symlink"
        private const val CHANNEL_ID = "engine"
        private const val NOTIFICATION_ID = 7
        private const val PAIR_CHANNEL_ID = "pairing"
        private const val PAIR_NOTIFICATION_ID = 8
        private const val TURN_TAIL_MS = 45_000L

        /** Host pids of the proot processes (diagnostics walks their trees). */
        @Volatile var acpPid: Int? = null
        @Volatile var bridgePid: Int? = null

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Empryo engine", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Empryo runs on this phone"
                    setShowBadge(false)
                }
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(PAIR_CHANNEL_ID, "Pair requests", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A phone or computer asks to pair with Empryo on this phone"
                }
            )
        }

        fun prefs(c: Context) = c.getSharedPreferences("engine", Context.MODE_PRIVATE)
        fun wantRunning(c: Context) = prefs(c).getBoolean("want_running", false)
        fun setWantRunning(c: Context, v: Boolean) = prefs(c).edit().putBoolean("want_running", v).apply()

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java).setAction(ACTION_START))

        fun pair(context: Context, on: Boolean) {
            if (!on && !EngineState.status.value.isOn) return
            ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java).setAction(if (on) ACTION_BRIDGE else ACTION_BRIDGE_STOP))
        }

        fun stop(context: Context) {
            if (!EngineState.status.value.isOn) { setWantRunning(context, false); return }
            ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java).setAction(ACTION_STOP))
        }

        fun portOpen(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 800); true }
        } catch (_: Exception) {
            false
        }
    }
}
