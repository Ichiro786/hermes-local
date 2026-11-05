package dev.syed.hermeslocal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** Visual evidence of the main screen: lever OFF, ON, and a frame sequence of it switching on. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi")
class LeverScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("shots.dir") ?: "build/shots"
    private val ready = SettingsSnapshot(passwordSet = true)

    @Before fun setUp() { AppController.init(RuntimeEnvironment.getApplication()) }

    private var status by mutableStateOf(EngineStatus())

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesTheme(amoled = true, dynamic = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { MainScreen(status, ready) {} }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
    }

    @Test fun leverOff() {
        status = EngineStatus(phase = Phase.Stopped)
        show()
        compose.onRoot().captureRoboImage("$out/lever-off.png")
    }

    @Test fun leverOn() {
        status = EngineStatus(phase = Phase.Running, runningSince = System.currentTimeMillis() - 754_000)
        show()
        compose.onRoot().captureRoboImage("$out/lever-on.png")
    }

    @Test fun leverAnimation() {
        status = EngineStatus(phase = Phase.Stopped)
        show()
        var f = 0
        fun frames(n: Int) = repeat(n) {
            compose.onRoot().captureRoboImage("$out/anim/f%03d.png".format(f++))
            compose.mainClock.advanceTimeBy(33)
        }
        frames(10)
        status = EngineStatus(phase = Phase.Starting)
        frames(18)
        status = EngineStatus(phase = Phase.Running, runningSince = System.currentTimeMillis())
        frames(14)
        status = EngineStatus(phase = Phase.Stopping)
        frames(12)
        status = EngineStatus(phase = Phase.Stopped)
        frames(14)
    }
}
