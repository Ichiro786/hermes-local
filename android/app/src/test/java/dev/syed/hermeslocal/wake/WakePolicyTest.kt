package dev.syed.hermeslocal.wake

import android.view.accessibility.AccessibilityEvent
import dev.syed.hermeslocal.engine.Phase
import dev.syed.hermeslocal.wake.WakePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The auto-wake decision table, the event filter and the accessibility-settings parsing (pure JVM). */
class WakePolicyTest {
    private val now = 10_000_000L
    private val ready = WakePolicy.Input(
        enabled = true, installed = true, installing = false, passwordSet = true,
        phase = Phase.Stopped, thermalStatus = 0, lastAttemptAt = 0L, now = now,
    )

    @Test fun stoppedEngineStarts() = assertEquals(Decision.Start, WakePolicy.decide(ready))

    @Test fun disabledNeverStarts() {
        for (p in Phase.entries) assertEquals(Decision.Disabled, WakePolicy.decide(ready.copy(enabled = false, phase = p)))
    }

    @Test fun runningOrStartingEngineIsLeftAlone() {
        assertEquals(Decision.AlreadyOn, WakePolicy.decide(ready.copy(phase = Phase.Running)))
        assertEquals(Decision.AlreadyOn, WakePolicy.decide(ready.copy(phase = Phase.Starting)))
        // even right after an attempt: AlreadyOn wins over Debounced, nothing is retried
        assertEquals(Decision.AlreadyOn, WakePolicy.decide(ready.copy(phase = Phase.Running, lastAttemptAt = now - 10)))
    }

    @Test fun stoppingEngineIsRestartedAfterTheStop() =
        assertEquals(Decision.Start, WakePolicy.decide(ready.copy(phase = Phase.Stopping)))

    @Test fun notReadyStates() {
        assertTrue(WakePolicy.decide(ready.copy(installed = false, phase = Phase.NotInstalled)) is Decision.NotReady)
        assertTrue(WakePolicy.decide(ready.copy(installing = true)) is Decision.NotReady)
        assertTrue(WakePolicy.decide(ready.copy(phase = Phase.Installing)) is Decision.NotReady)
        assertTrue(WakePolicy.decide(ready.copy(passwordSet = false)) is Decision.NotReady)
        assertTrue(WakePolicy.decide(ready.copy(thermalStatus = WakePolicy.THERMAL_SEVERE)) is Decision.NotReady)
        assertEquals(Decision.Start, WakePolicy.decide(ready.copy(thermalStatus = WakePolicy.THERMAL_SEVERE - 1)))
    }

    @Test fun repeatedEventsAreDebounced() {
        assertEquals(Decision.Debounced, WakePolicy.decide(ready.copy(lastAttemptAt = now - 1)))
        assertEquals(Decision.Debounced, WakePolicy.decide(ready.copy(lastAttemptAt = now - WakePolicy.DEBOUNCE_MS + 1)))
        assertEquals(Decision.Start, WakePolicy.decide(ready.copy(lastAttemptAt = now - WakePolicy.DEBOUNCE_MS)))
    }

    @Test fun failedAutoStartBacksOffThenRetries() {
        val failed = ready.copy(phase = Phase.Failed)
        assertEquals(Decision.Start, WakePolicy.decide(failed)) // a failure that auto-wake did not cause: try
        assertEquals(Decision.BackingOff, WakePolicy.decide(failed.copy(lastAttemptAt = now - WakePolicy.DEBOUNCE_MS - 1)))
        assertEquals(Decision.Start, WakePolicy.decide(failed.copy(lastAttemptAt = now - WakePolicy.FAILURE_BACKOFF_MS)))
    }

    @Test fun anEventStampedBeforeTheLastAttemptIsDebounced() =
        // two events raced: the later-stamped one won the lock first; the other must not start a second engine
        assertEquals(Decision.Debounced, WakePolicy.decide(ready.copy(lastAttemptAt = now + 40)))

    @Test fun onlyTheOfficialAppsWindowChangesCount() {
        val t = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        assertTrue(WakePolicy.isHermesForeground(t, "ai.nousresearch.hermes.mobile"))
        assertFalse(WakePolicy.isHermesForeground(t, "com.m57.hermescontrol"))
        assertFalse(WakePolicy.isHermesForeground(t, "ai.nousresearch.hermes.mobile.evil"))
        assertFalse(WakePolicy.isHermesForeground(t, "dev.syed.hermeslocal"))
        assertFalse(WakePolicy.isHermesForeground(t, null))
        assertFalse(WakePolicy.isHermesForeground(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "ai.nousresearch.hermes.mobile"))
        assertFalse(WakePolicy.isHermesForeground(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "ai.nousresearch.hermes.mobile"))
    }

    @Test fun enabledServicesParsing() {
        val pkg = "dev.syed.hermeslocal"; val cls = "dev.syed.hermeslocal.wake.AutoWakeService"
        assertTrue(WakePolicy.isServiceEnabled("$pkg/$cls", pkg, cls))
        assertTrue(WakePolicy.isServiceEnabled("$pkg/.wake.AutoWakeService", pkg, cls))
        assertTrue(WakePolicy.isServiceEnabled("com.other/.A:$pkg/$cls:com.x/y.Z", pkg, cls))
        assertTrue(WakePolicy.isServiceEnabled("DEV.SYED.HERMESLOCAL/DEV.SYED.HERMESLOCAL.WAKE.AUTOWAKESERVICE", pkg, cls))
        assertFalse(WakePolicy.isServiceEnabled(null, pkg, cls))
        assertFalse(WakePolicy.isServiceEnabled("", pkg, cls))
        assertFalse(WakePolicy.isServiceEnabled("com.other/.A", pkg, cls))
        assertFalse(WakePolicy.isServiceEnabled("$pkg/.Other", pkg, cls))
        assertFalse(WakePolicy.isServiceEnabled("$pkg.evil/$cls", pkg, cls))
    }
}
