package dev.syed.hermeslocal.wake

import android.app.Application
import android.provider.Settings as AndroidSettings
import dev.syed.hermeslocal.engine.EnginePaths
import dev.syed.hermeslocal.engine.EngineState
import dev.syed.hermeslocal.engine.Phase
import dev.syed.hermeslocal.engine.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Auto-wake against real app state: one start per burst of events, setup states, access revoked, opt-out kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutoWakeTest {
    private lateinit var app: Application
    private val component get() = "${app.packageName}/${AutoWakeService::class.java.name}"

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.applicationInfo.nativeLibraryDir = app.cacheDir.path
        AutoWake.resetForTest()
        EngineState.set { it.copy(phase = Phase.Stopped, message = null) }
        // An installed engine with a password: what the lever needs too.
        File(EnginePaths(app).rootfs, "opt/hermes-local/bin").mkdirs()
        EnginePaths(app).startScript.writeText("#!/bin/bash\n")
        Settings.get(app).update { copy(passwordSet = true, autoWake = true) }
    }

    @After fun tearDown() {
        AutoWake.resetForTest()
        EngineState.set { it.copy(phase = Phase.Stopped) }
        grant(false)
    }

    private fun grant(on: Boolean) {
        AndroidSettings.Secure.putInt(app.contentResolver, AndroidSettings.Secure.ACCESSIBILITY_ENABLED, if (on) 1 else 0)
        AndroidSettings.Secure.putString(app.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (on) "com.other/.A:$component" else "")
    }

    @Test fun burstOfEventsFromManyThreadsStartsOnce() {
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val t0 = 50_000_000L
        val results = (0 until 64).map { i ->
            pool.submit<WakePolicy.Decision> { go.await(); AutoWake.onHermesForeground(app, now = t0 + i) }
        }
        go.countDown()
        val decisions = results.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, decisions.count { it == WakePolicy.Decision.Start })
        assertEquals(63, decisions.count { it == WakePolicy.Decision.Debounced })
        // Hermes opened again later: one more attempt.
        assertEquals(WakePolicy.Decision.Start, AutoWake.onHermesForeground(app, now = t0 + WakePolicy.DEBOUNCE_MS + 100))
    }

    @Test fun runningEngineIsNeverStartedAgain() {
        EngineState.set { it.copy(phase = Phase.Running) }
        repeat(5) { assertEquals(WakePolicy.Decision.AlreadyOn, AutoWake.onHermesForeground(app, now = 60_000_000L + it * 20_000)) }
        EngineState.set { it.copy(phase = Phase.Starting) }
        assertEquals(WakePolicy.Decision.AlreadyOn, AutoWake.onHermesForeground(app, now = 61_000_000L))
    }

    @Test fun toggleOffMeansNoStart() {
        AutoWake.setEnabled(app, false)
        assertEquals(WakePolicy.Decision.Disabled, AutoWake.onHermesForeground(app, now = 70_000_000L))
    }

    @Test fun noPasswordOrNoEngineMeansNoStart() {
        Settings.get(app).update { copy(passwordSet = false) }
        assertTrue(AutoWake.onHermesForeground(app, now = 80_000_000L) is WakePolicy.Decision.NotReady)
        Settings.get(app).update { copy(passwordSet = true) }
        EnginePaths(app).startScript.delete()
        assertTrue(AutoWake.onHermesForeground(app, now = 81_000_000L) is WakePolicy.Decision.NotReady)
        assertTrue(AutoWake.last.value!!.result.contains("not installed"))
    }

    @Test fun setupStatesFollowAccessAndBinding() {
        AutoWake.setEnabled(app, false)
        assertEquals(AutoWake.Setup.Off, AutoWake.setup(app))
        AutoWake.setEnabled(app, true)
        assertEquals(AutoWake.Setup.NeedsAccess, AutoWake.setup(app))
        grant(true)
        assertTrue(AutoWake.accessGranted(app))
        assertEquals(AutoWake.Setup.NotRunning, AutoWake.setup(app)) // granted, not bound (e.g. a vendor ROM stopped it)
        val service = Robolectric.buildService(AutoWakeService::class.java).create().get()
        AutoWake.onServiceConnected(service)
        assertEquals(AutoWake.Setup.Active, AutoWake.setup(app))
        // Access revoked in Android's settings: the system unbinds the service.
        grant(false)
        AutoWake.onServiceDisconnected(service)
        assertFalse(AutoWake.connected.value)
        assertEquals(AutoWake.Setup.NeedsAccess, AutoWake.setup(app))
    }

    @Test fun enablingInAndroidSettingsOptsIn() {
        Settings.get(app).update { copy(autoWake = false) } // never decided in the app
        app.getSharedPreferences("auto_wake", 0).edit().clear().commit()
        val service = Robolectric.buildService(AutoWakeService::class.java).create().get()
        AutoWake.onServiceConnected(service)
        assertTrue(Settings.get(app).value.autoWake)
    }

    @Test fun turnedOffInTheAppStaysOffWhenTheSystemRebinds() {
        AutoWake.setEnabled(app, false)
        val service = Robolectric.buildService(AutoWakeService::class.java).create().get()
        AutoWake.onServiceConnected(service) // e.g. after a reboot, before disableSelf took effect
        assertFalse(Settings.get(app).value.autoWake)
        assertEquals(WakePolicy.Decision.Disabled, AutoWake.onHermesForeground(app, now = 90_000_000L))
    }

    @Test fun serviceIgnoresOtherPackagesAndEventTypes() {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofHours(1)) // a realistic elapsedRealtime
        val service = Robolectric.buildService(AutoWakeService::class.java).create().get()
        fun event(type: Int, pkg: String) = android.view.accessibility.AccessibilityEvent.obtain(type).apply { packageName = pkg }
        service.onAccessibilityEvent(event(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.other.app"))
        service.onAccessibilityEvent(event(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED, WakePolicy.OFFICIAL_HERMES))
        assertEquals(null, AutoWake.last.value) // nothing was attempted or recorded
        // The official app's window: an attempt (a start is due; it is then debounced).
        service.onAccessibilityEvent(event(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, WakePolicy.OFFICIAL_HERMES))
        assertEquals(WakePolicy.Decision.Debounced, AutoWake.onHermesForeground(app))
    }
}
