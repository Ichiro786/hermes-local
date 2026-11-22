package dev.syed.hermeslocal.wake

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.syed.hermeslocal.R
import dev.syed.hermeslocal.engine.EnginePaths
import dev.syed.hermeslocal.engine.EngineService
import dev.syed.hermeslocal.engine.EngineState
import dev.syed.hermeslocal.engine.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * "Auto-wake with official Hermes": when [AutoWakeService] reports the official Hermes app in front, start the
 * local engine through the same [EngineService] the lever uses, once, if it is not on already. It never stops
 * the engine (Hermes leaving the front, the screen turning off, or the service going away change nothing).
 */
object AutoWake {
    private const val TAG = "HermesLocalWake"
    private const val CHANNEL_ID = "auto_wake"
    private const val NOTIFICATION_ID = 9

    enum class Setup {
        /** The toggle is off. */
        Off,
        /** The toggle is on, Android's accessibility access for the service is not granted (or was revoked). */
        NeedsAccess,
        /** Granted, but the system has not bound the service (it was killed, or a vendor ROM stopped it). */
        NotRunning,
        /** Bound: Hermes coming to the front wakes the engine. */
        Active,
    }

    data class LastWake(val atMillis: Long, val result: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    @Volatile private var lastAttemptAt = 0L
    private var service: WeakReference<AutoWakeService>? = null

    private val _connected = MutableStateFlow(false)
    /** True while the system has [AutoWakeService] bound (in this process). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _last = MutableStateFlow<LastWake?>(null)
    val last: StateFlow<LastWake?> = _last.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("auto_wake", Context.MODE_PRIVATE)

    fun loadLast(context: Context) {
        val p = prefs(context)
        val at = p.getLong("last_at", 0L)
        if (at > 0 && _last.value == null) _last.value = LastWake(at, p.getString("last_result", "") ?: "")
    }

    private fun record(context: Context, result: String) {
        val w = LastWake(System.currentTimeMillis(), result)
        _last.value = w
        prefs(context).edit().putLong("last_at", w.atMillis).putString("last_result", result).apply()
        Log.i(TAG, "auto-wake: $result")
    }

    /** Tests only: forget the last attempt and the bound service. */
    internal fun resetForTest() {
        synchronized(lock) { lastAttemptAt = 0L }
        service = null
        _connected.value = false
        _last.value = null
    }

    // --- service lifecycle -------------------------------------------------------------------------------------

    internal fun onServiceConnected(s: AutoWakeService) {
        service = WeakReference(s)
        _connected.value = true
        val settings = Settings.get(s)
        if (!settings.value.autoWake) {
            // Turned off in Hermes Local: give the access back. Never decided: turning the service on in Android's
            // accessibility settings is itself the opt-in.
            if (prefs(s).getBoolean("user_off", false)) s.disableSelfCompat() else settings.update { copy(autoWake = true) }
        }
        Log.i(TAG, "service connected")
    }

    internal fun onServiceDisconnected(s: AutoWakeService) {
        if (service?.get() === s) service = null
        _connected.value = false
        Log.i(TAG, "service disconnected")
    }

    // --- the trigger -------------------------------------------------------------------------------------------

    /** Called for every window-state event the service receives (only the official app's, by its XML filter). */
    fun onHermesForeground(context: Context, now: Long = SystemClock.elapsedRealtime()): WakePolicy.Decision {
        val app = context.applicationContext
        val settings = Settings.get(app)
        val s = settings.value
        val status = EngineState.status.value
        val decision = synchronized(lock) {
            WakePolicy.decide(
                WakePolicy.Input(
                    enabled = s.autoWake,
                    installed = EnginePaths(app).isInstalled(),
                    installing = dev.syed.hermeslocal.AppController.isInstalling,
                    passwordSet = s.passwordSet,
                    phase = status.phase,
                    thermalStatus = thermalStatus(app),
                    lastAttemptAt = lastAttemptAt,
                    now = now,
                )
            ).also { if (it == WakePolicy.Decision.Start) lastAttemptAt = now }
        }
        when (decision) {
            WakePolicy.Decision.Start -> start(app, s.port)
            WakePolicy.Decision.Disabled -> service?.get()?.disableSelfCompat() // the toggle is off: give up access
            is WakePolicy.Decision.NotReady -> record(app, "Hermes opened, engine not started: ${decision.reason}")
            WakePolicy.Decision.BackingOff -> record(app, "Hermes opened, not retrying yet: the last auto-start failed")
            WakePolicy.Decision.AlreadyOn, WakePolicy.Decision.Debounced -> Unit
        }
        return decision
    }

    private fun start(app: Context, port: Int) {
        scope.launch {
            // An engine already answering on the port (for example one the system restarted a moment ago) is left
            // alone: a second Hermes on the same port would only fail.
            if (withContext(Dispatchers.IO) { EngineService.probe(port) }) {
                record(app, "Hermes opened, the server was already answering")
                return@launch
            }
            try {
                EngineService.start(app, EngineService.SOURCE_AUTO_WAKE)
                record(app, "Hermes opened, started the engine")
            } catch (e: Exception) {
                // Android 12+ refuses a foreground-service start from the background unless the app is exempt
                // (bound accessibility service, battery-optimisation exemption...). A tap on a notification is
                // always allowed, so offer that instead of failing silently.
                Log.w(TAG, "background start refused", e)
                notifyTapToStart(app)
                record(app, "Android blocked the background start; tap the notification to start Hermes")
            }
        }
    }

    private fun thermalStatus(app: Context): Int =
        runCatching { app.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrDefault(0)

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is requested at app start; without it notify is a no-op
    private fun notifyTapToStart(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Auto-wake", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Asks you to tap when Android does not let Hermes Local start in the background"
                setShowBadge(false)
            }
        )
        val start = PendingIntent.getForegroundService(
            app, 3, Intent(app, EngineService::class.java).setAction(EngineService.ACTION_START)
                .putExtra(EngineService.EXTRA_SOURCE, EngineService.SOURCE_AUTO_WAKE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Start Hermes Local?")
            .setContentText("Hermes opened. Tap to start the local server.")
            .setContentIntent(start)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    fun cancelTapToStart(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }

    // --- setup state + navigation ------------------------------------------------------------------------------

    fun component(context: Context) = ComponentName(context.packageName, AutoWakeService::class.java.name)

    fun accessGranted(context: Context): Boolean {
        val cr = context.contentResolver
        val on = runCatching { AndroidSettings.Secure.getInt(cr, AndroidSettings.Secure.ACCESSIBILITY_ENABLED) == 1 }.getOrDefault(false)
        val list = runCatching { AndroidSettings.Secure.getString(cr, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull()
        return on && WakePolicy.isServiceEnabled(list, context.packageName, AutoWakeService::class.java.name)
    }

    fun setup(context: Context): Setup = when {
        !Settings.get(context).value.autoWake -> Setup.Off
        !accessGranted(context) -> Setup.NeedsAccess
        !_connected.value -> Setup.NotRunning
        else -> Setup.Active
    }

    fun officialHermesInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(WakePolicy.OFFICIAL_HERMES) != null

    /** The toggle: on asks for access when needed; off also gives the access back (the service unbinds). */
    fun setEnabled(context: Context, on: Boolean) {
        Settings.get(context).update { copy(autoWake = on) }
        prefs(context).edit().putBoolean("user_off", !on).apply()
        if (!on) {
            service?.get()?.disableSelfCompat()
            cancelTapToStart(context)
        }
    }

    /** Android's accessibility settings, scrolled to this service where the Settings app supports it. */
    fun openAccessibilitySettings(context: Context) {
        val key = component(context).flattenToString()
        val args = android.os.Bundle().apply { putString(":settings:fragment_args_key", key) }
        val intent = Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", key).putExtra(":settings:show_fragment_args", args)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** App info: "Allow restricted settings" (sideloaded apps, Android 13+) and battery / background activity. */
    fun openAppInfo(context: Context) {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** OPPO / OnePlus / realme (ColorOS, OxygenOS 14+): known to stop background apps' services. */
    val isColorOs: Boolean get() = Build.MANUFACTURER.lowercase() in setOf("oppo", "oneplus", "realme")
}
