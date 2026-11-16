package dev.syed.hermeslocal.engine

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime

/**
 * Battery and heat policy while Hermes runs (optimize-research section 4).
 *
 * The CPU may sleep unless something needs it: the partial wake lock is held only for
 *  - an active turn: the engine's processes used more than [ACTIVE_CPU] of a core over the last sample,
 *    plus a [TAIL_MS] tail (idle Hermes is ~1.3 %);
 *  - a due cron job: an inexact alarm wakes the phone a minute before the next `next_run_at` in
 *    ~/.hermes/cron/jobs.json and holds the lock for [CRON_HOLD_MS];
 *  - LAN access (other devices can call in at any time; also keeps Wi-Fi in high-performance mode);
 *  - "Keep awake always" in Settings (the old behaviour).
 * Heat: from MODERATE the engine's threads are niced to 10 (until the next start); from SEVERE an idle
 * engine is stopped ([onTooHot]); a turn in progress is allowed to finish first.
 */
class PowerGovernor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTooHot: () -> Unit,
) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val am = context.getSystemService(AlarmManager::class.java)
    private val paths = EnginePaths(context)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var job: Job? = null
    private var settings = SettingsSnapshot()
    @Volatile private var activeUntil = 0L
    @Volatile private var cronUntil = 0L
    private var lastTicks = -1L
    private var lastSample = 0L
    private var reniced = false
    private var alarmAt = 0L
    private val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        EngineState.set { it.copy(thermal = status) }
        if (status >= PowerManager.THERMAL_STATUS_MODERATE && !reniced) scope.launch { renice() }
        if (status >= PowerManager.THERMAL_STATUS_SEVERE && System.currentTimeMillis() > activeUntil) onTooHot()
    }

    fun start(s: SettingsSnapshot) {
        settings = s
        reniced = false
        activeUntil = System.currentTimeMillis() + TAIL_MS // starting up is work too
        runCatching { pm.addThermalStatusListener(context.mainExecutor, thermalListener) }
        apply()
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                sample()
                scheduleCronAlarm()
                apply()
                delay(SAMPLE_MS)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { pm.removeThermalStatusListener(thermalListener) }
        cancelAlarm()
        release()
        EngineState.set { it.copy(awake = null) }
    }

    /** The cron alarm fired (the service got [ACTION_CRON_WAKE]). */
    fun onCronWake() {
        cronUntil = System.currentTimeMillis() + CRON_HOLD_MS
        alarmAt = 0L
        apply()
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val ticks = engineCpuTicks()
        if (lastTicks >= 0 && now > lastSample) {
            val coreShare = (ticks - lastTicks).toDouble() / clkTck / ((now - lastSample) / 1000.0)
            if (coreShare >= ACTIVE_CPU) activeUntil = now + TAIL_MS
        }
        lastTicks = ticks; lastSample = now
        if (EngineState.status.value.thermal >= PowerManager.THERMAL_STATUS_SEVERE && now > activeUntil) onTooHot()
    }

    @SuppressLint("WakelockTimeout")
    private fun apply() {
        val now = System.currentTimeMillis()
        val reason = when {
            settings.keepAwake -> "Always awake (setting)"
            settings.lanAccess -> "LAN access"
            now < cronUntil -> "Cron job due"
            now < activeUntil -> "Working"
            else -> null
        }
        if (reason != null) {
            val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesLocal:engine").apply { setReferenceCounted(false) }
            wakeLock = wl
            wl.acquire(SAMPLE_MS * 3) // renewed every sample; can never leak past ~30 s if the loop dies
        } else wakeLock?.let { if (it.isHeld) it.release() }
        if (settings.lanAccess && wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HermesLocal:lan").apply { setReferenceCounted(false); acquire() }
        }
        if (EngineState.status.value.awake != reason) EngineState.set { it.copy(awake = reason) }
    }

    private fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
    }

    /** utime+stime of every process of this app except the UI/service process: that is proot and the guests. */
    private fun engineCpuTicks(): Long {
        val me = Process.myPid().toString()
        var sum = 0L
        File("/proc").list()?.forEach { pid ->
            if (pid == me || pid.isEmpty() || !pid[0].isDigit()) return@forEach
            runCatching {
                val stat = File("/proc/$pid/stat").readText()
                val f = stat.substring(stat.lastIndexOf(')') + 2).split(' ') // fields from #3 (state)
                if (File("/proc/$pid").let { Os.stat(it.path).st_uid } == Process.myUid()) sum += f[11].toLong() + f[12].toLong()
            }
        }
        return sum
    }

    /** Nice 10 for every engine thread (Android lets an app lower its own priority, not raise it back). */
    private fun renice() {
        reniced = true
        val me = Process.myPid().toString()
        File("/proc").list()?.filter { it != me && it.firstOrNull()?.isDigit() == true }?.forEach { pid ->
            File("/proc/$pid/task").list()?.forEach { tid -> runCatching { Process.setThreadPriority(tid.toInt(), 10) } }
        }
    }

    private fun scheduleCronAlarm() {
        val next = nextCronRun() ?: run { cancelAlarm(); return }
        val now = System.currentTimeMillis()
        if (next - now < LEAD_MS) { cronUntil = maxOf(cronUntil, next + CRON_HOLD_MS); return }
        val at = next - LEAD_MS
        if (at == alarmAt) return
        alarmAt = at
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, cronIntent())
    }

    private fun cancelAlarm() { if (alarmAt != 0L) am.cancel(cronIntent()); alarmAt = 0L }

    private fun cronIntent() = PendingIntent.getForegroundService(
        context, 2, Intent(context, EngineService::class.java).setAction(EngineService.ACTION_CRON_WAKE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Earliest `next_run_at` of an enabled job, as epoch ms. */
    private fun nextCronRun(): Long? = runCatching {
        val jobs = JSONObject(File(paths.rootfs, "root/.hermes/cron/jobs.json").readText()).optJSONArray("jobs") ?: return null
        (0 until jobs.length()).mapNotNull { i ->
            val j = jobs.getJSONObject(i)
            if (!j.optBoolean("enabled", true) || j.optString("state") == "paused") return@mapNotNull null
            j.optString("next_run_at").takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        }.minOrNull()
    }.getOrNull()

    companion object {
        const val SAMPLE_MS = 10_000L
        const val TAIL_MS = 60_000L
        const val ACTIVE_CPU = 0.05 // share of one core; idle Hermes measured ~0.013
        const val LEAD_MS = 60_000L
        const val CRON_HOLD_MS = 5 * 60_000L
    }
}
