package dev.syed.hermeslocal.wake

import android.view.accessibility.AccessibilityEvent
import dev.syed.hermeslocal.engine.Phase

/**
 * Pure decisions of "Auto-wake with official Hermes" (no Android state in here, so it is unit-tested on the JVM).
 *
 * Only one signal is used: the official Hermes app's window came to the front (TYPE_WINDOW_STATE_CHANGED with
 * its package name). Nothing else of the event is read: no text, no view tree, no other app.
 */
object WakePolicy {
    /** The official Hermes Android app (Nous Research). The accessibility service is limited to it in XML too. */
    const val OFFICIAL_HERMES = "ai.nousresearch.hermes.mobile"

    /** Hermes opens a few windows in a row (splash, main, a dialog): one start attempt per this window. */
    const val DEBOUNCE_MS = 15_000L

    /** After an auto-start that ended in Failed, opening Hermes again does not retry for this long. */
    const val FAILURE_BACKOFF_MS = 120_000L

    /** android.os.PowerManager.THERMAL_STATUS_SEVERE: the engine's own governor would stop it again. */
    const val THERMAL_SEVERE = 3

    fun isHermesForeground(eventType: Int, packageName: CharSequence?): Boolean =
        eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && packageName?.toString() == OFFICIAL_HERMES

    sealed interface Decision {
        /** Start the engine (the caller records [now] as the last attempt). */
        data object Start : Decision
        data object Disabled : Decision
        /** Starting or Running already: nothing to do (never a second start). */
        data object AlreadyOn : Decision
        /** Within [DEBOUNCE_MS] of the last attempt. */
        data object Debounced : Decision
        /** The last auto-start failed less than [FAILURE_BACKOFF_MS] ago. */
        data object BackingOff : Decision
        /** The engine cannot start yet; [reason] is shown in Settings. */
        data class NotReady(val reason: String) : Decision
    }

    data class Input(
        val enabled: Boolean,
        val installed: Boolean,
        val installing: Boolean,
        val passwordSet: Boolean,
        val phase: Phase,
        val thermalStatus: Int,
        /** elapsedRealtime of the last auto-start attempt, 0 = none. */
        val lastAttemptAt: Long,
        val now: Long,
    )

    fun decide(i: Input): Decision = when {
        !i.enabled -> Decision.Disabled
        i.installing || i.phase == Phase.Installing -> Decision.NotReady("The engine is being installed")
        !i.installed || i.phase == Phase.NotInstalled -> Decision.NotReady("The engine is not installed")
        !i.passwordSet -> Decision.NotReady("Set a dashboard password first")
        i.phase == Phase.Starting || i.phase == Phase.Running -> Decision.AlreadyOn
        // lastAttemptAt is kept in memory only and elapsedRealtime never goes back, so an event stamped before the
        // last attempt is one that raced it on another thread: debounced as well, never a second start.
        i.lastAttemptAt > 0 && i.now - i.lastAttemptAt < DEBOUNCE_MS -> Decision.Debounced
        i.phase == Phase.Failed && i.lastAttemptAt > 0 && i.now - i.lastAttemptAt < FAILURE_BACKOFF_MS -> Decision.BackingOff
        i.thermalStatus >= THERMAL_SEVERE -> Decision.NotReady("The phone is too hot")
        // Stopped, Failed (manual start or old failure) and Stopping (the service queues the start after the stop).
        else -> Decision.Start
    }

    /**
     * True when [enabledServices] (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "pkg/cls:pkg/cls") lists the
     * service [pkg]/[cls]; the class may be written in full or relative to the package (".wake.AutoWakeService").
     */
    fun isServiceEnabled(enabledServices: String?, pkg: String, cls: String): Boolean {
        if (enabledServices.isNullOrBlank()) return false
        val short = if (cls.startsWith("$pkg.")) cls.removePrefix(pkg) else cls
        return enabledServices.split(':').any { entry ->
            val parts = entry.trim().split('/', limit = 2)
            parts.size == 2 && parts[0].equals(pkg, ignoreCase = true) &&
                (parts[1].equals(cls, ignoreCase = true) || parts[1].equals(short, ignoreCase = true))
        }
    }
}
