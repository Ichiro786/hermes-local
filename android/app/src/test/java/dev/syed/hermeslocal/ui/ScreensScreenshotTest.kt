package dev.syed.hermeslocal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.syed.hermeslocal.AppController
import dev.syed.hermeslocal.engine.EngineStatus
import dev.syed.hermeslocal.engine.Phase
import dev.syed.hermeslocal.engine.SettingsSnapshot
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Design previews of the test5 redesign (Empryo Local look), dark and AMOLED, before the emulator run. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-night-xxhdpi")
class ScreensScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("shots.dir") ?: "build/shots"
    private val ready = SettingsSnapshot(passwordSet = true, amoled = false, dynamicColor = false)

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.nativeLibraryDir = app.cacheDir.path // Robolectric leaves it null; EnginePaths needs a path
        AppController.init(app)
    }

    private fun shot(name: String, amoled: Boolean = false, ms: Long = 1500, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesTheme(amoled = amoled, dynamic = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(ms)
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    @Test fun mainOff() = shot("t5-main-off") { MainScreen(EngineStatus(phase = Phase.Stopped), ready) {} }
    @Test fun mainRunning() = shot("t5-main-running") {
        MainScreen(EngineStatus(phase = Phase.Running, runningSince = System.currentTimeMillis() - 45_240_000, awake = "Working", thermal = 1), ready) {}
    }
    @Test fun mainRunningAmoled() = shot("t5-main-running-amoled", amoled = true) {
        MainScreen(EngineStatus(phase = Phase.Running, runningSince = System.currentTimeMillis() - 754_000), ready.copy(amoled = true)) {}
    }
    @Test fun mainDownloading() = shot("t5-main-download") {
        MainScreen(EngineStatus(phase = Phase.Installing, installStage = "Downloading engine", installProgress = 0.42f), SettingsSnapshot()) {}
    }
    @Test fun mainNotInstalled() = shot("t5-main-not-installed") {
        MainScreen(EngineStatus(phase = Phase.NotInstalled, message = "Engine install failed: Unable to resolve host \"github.com\""), SettingsSnapshot()) {}
    }
    @Test fun settings() = shot("t5-settings") { SettingsScreen(EngineStatus(phase = Phase.Stopped), ready, onBack = {}, onNavigate = {}) }
    @Test fun storage() = shot("t5-storage", ms = 4000) { StorageScreen(EngineStatus(phase = Phase.Stopped), onBack = {}) }
    @Test fun logs() = shot("t5-logs") { LogsScreen(onBack = {}) }
    @Test fun providers() = shot("t5-providers", ms = 3000) { ProvidersScreen(EngineStatus(phase = Phase.Stopped), onBack = {}) }
}
