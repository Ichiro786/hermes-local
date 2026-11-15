package run.hermes.empryo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.hermes.empryo.engine.ChatItem
import run.hermes.empryo.engine.ChatUi
import run.hermes.empryo.engine.EngineStatus
import run.hermes.empryo.engine.Permission
import run.hermes.empryo.engine.Phase
import run.hermes.empryo.engine.SettingsSnapshot
import run.hermes.empryo.engine.Thermal

/** Screens for Syed: engine off, running, settings, setup, chat (Roborazzi, no device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-night-xxhdpi")
class ScreensShotTest {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("shots.dir") ?: "build/shots"

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EmpryoTheme {
                // Same root as MainActivity: a Surface provides the content colour.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    private fun main(status: EngineStatus) = shot(
        when (status.phase) { Phase.Running -> "01-running"; Phase.Installing -> "04-setup"; else -> "00-off" },
    ) {
        MainScreen(status, Thermal.Reading(0, 0.31f), "3.12.8-beta", {}, {}, {}, {})
    }

    @Test fun off() = main(EngineStatus(phase = Phase.Stopped))

    @Test fun running() = main(EngineStatus(phase = Phase.Running, runningSince = System.currentTimeMillis() - (12 * 3600 + 34 * 60) * 1000L))

    @Test fun setup() = main(EngineStatus(phase = Phase.Installing, installStage = "Downloading Empryo 3.12.8-beta", installProgress = 0.42f))

    @Test fun settings() = shot("02-settings") {
        SettingsScreen(SettingsSnapshot(keepAwake = true), engineOn = false, onBack = {}, onNavigate = {}, onUpdate = {}, onShareLog = {})
    }

    @Test fun chat() = shot("03-chat") {
        ChatScreen(
            ChatUi(
                sessionId = "s",
                items = listOf(
                    ChatItem.Note("New session · anthropic/claude-sonnet-4-5 · /root/workspace"),
                    ChatItem.User("What's in this folder?"),
                    ChatItem.Tool("1", "ls -la", "completed", "total 8\ndrwxr-xr-x 2 root root 4096 ."),
                    ChatItem.Agent("a", "The workspace is empty. Want me to clone a repo?"),
                    ChatItem.User("Clone github.com/Ichiro786/lastchat"),
                ),
                busy = true,
                permission = Permission(0, "git clone https://github.com/Ichiro786/lastchat", listOf("allow_once" to "allow_once", "reject_once" to "reject_once")),
            ),
            engineRunning = true, onBack = {}, onNew = {}, onSend = {}, onStop = {}, onPermission = {},
        )
    }
}
