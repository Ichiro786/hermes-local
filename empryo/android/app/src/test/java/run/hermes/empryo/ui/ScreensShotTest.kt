package run.hermes.empryo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import org.json.JSONObject
import run.hermes.empryo.LoginUi
import run.hermes.empryo.engine.ChatItem
import run.hermes.empryo.engine.PairRequest
import run.hermes.empryo.engine.PairUi
import run.hermes.empryo.engine.ChatUi
import run.hermes.empryo.engine.EngineStatus
import run.hermes.empryo.engine.Permission
import run.hermes.empryo.engine.Phase
import run.hermes.empryo.engine.SettingsSnapshot
import run.hermes.empryo.engine.Thermal

/** Screens for Syed: engine off, running, settings, setup, chat (Roborazzi, no device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h915dp-night-xxhdpi")
class ScreensShotTest {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("shots.dir") ?: "build/shots"

    private fun shot(name: String, fontScale: Float = 1f, amoled: Boolean = false, dynamic: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                EmpryoTheme(amoled = amoled, dynamic = dynamic) {
                    // Same root as MainActivity: a Surface provides the content colour.
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    private fun main(status: EngineStatus) = shot(
        when (status.phase) { Phase.Running -> "01-running"; Phase.Installing -> "04-setup"; else -> "00-off" },
    ) {
        MainScreen(status, Thermal.Reading(0, 0.90f), "3.12.8-beta", {}, {}, {}, {})
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

    private fun pairUi(request: PairRequest? = null): PairUi {
        val d = JSONObject(javaClass.classLoader!!.getResource("pair-info.json")!!.readText())
        val cells = d.getJSONObject("qr").getJSONArray("cells")
        return PairUi(
            state = "ready", via = "quick", code = d.getString("code"), link = d.getString("link"),
            qr = (0 until cells.length()).map { cells.getString(it) }, reaches = listOf("quick relay.empryo.com/-4cRVpIF"),
            pairingOpen = true, openedAt = System.currentTimeMillis() - 23_000, request = request,
            log = listOf("verifying · Syed's iPhone · wifi"),
        )
    }

    @Test fun pair() = shot("05-pair") {
        PairScreen(pairUi(), pairingOn = true, engineRunning = true, lanAccess = false, onBack = {}, onSetPairing = {}, onOpen = {}, onVisible = {}, onAnswer = {}, onCopy = {})
    }

    @Test fun pairRequest() = shot("06-pair-request") {
        PairScreen(
            pairUi(PairRequest("x", "Syed's iPhone", "wifi", "428 913", computer = false, unverified = false)),
            pairingOn = true, engineRunning = true, lanAccess = true, onBack = {}, onSetPairing = {}, onOpen = {}, onVisible = {}, onAnswer = {}, onCopy = {},
        )
    }

    @Test fun accounts() = shot("07-accounts") {
        AccountsScreen(
            LoginUi(account = "codex-device", running = true, code = "J8XX-JT5Q2", url = "https://auth.openai.com/codex/device",
                lines = listOf("Enter code: J8XX-JT5Q2", "Go to: https://auth.openai.com/codex/device", "Waiting for authorization.")),
            onBack = {}, onLogin = {}, onLoginInput = {}, onCancelLogin = {}, onOpen = {}, onCopy = {}, onSetKey = { _, _ -> "" }, onListModels = { "" },
            defaultModel = "", onSaveModel = {},
        )
    }

    private val runningStatus get() = EngineStatus(
        phase = Phase.Running, runningSince = System.currentTimeMillis() - (24 * 60 + 59) * 1000L, bridge = "Exited (1): reclaimed-stale",
    )

    // Syed's phone: larger font and display size (fontScale 1.3 on a 412 dp wide screen).
    @Test fun runningLargeFont() = shot("08-running-font1.3", fontScale = 1.3f) {
        MainScreen(runningStatus, Thermal.Reading(0, 0.20f), "3.12.8-beta", {}, {}, {}, {})
    }

    @Test fun settingsLargeFont() = shot("09-settings-font1.3", fontScale = 1.3f) {
        SettingsScreen(SettingsSnapshot(), engineOn = true, onBack = {}, onNavigate = {}, onUpdate = {}, onShareLog = {})
    }

    @Test fun runningAmoled() = shot("10-running-amoled", amoled = true) {
        MainScreen(runningStatus, Thermal.Reading(0, 0.20f), "3.12.8-beta", {}, {}, {}, {})
    }

    @Test fun settingsAmoled() = shot("11-settings-amoled", amoled = true) {
        SettingsScreen(SettingsSnapshot(amoled = true), engineOn = false, onBack = {}, onNavigate = {}, onUpdate = {}, onShareLog = {})
    }

    // Robolectric's system palette stands in for a wallpaper here; the phone shows its own.
    @Test fun offDynamic() = shot("12-off-dynamic", dynamic = true) {
        MainScreen(EngineStatus(phase = Phase.Stopped), Thermal.Reading(0, 0.20f), "3.12.8-beta", {}, {}, {}, {})
    }

    @Test fun runningDynamicAmoled() = shot("13-running-dynamic-amoled", amoled = true, dynamic = true) {
        MainScreen(runningStatus, Thermal.Reading(0, 0.20f), "3.12.8-beta", {}, {}, {}, {})
    }
}
