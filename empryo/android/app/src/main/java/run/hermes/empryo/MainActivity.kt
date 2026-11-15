package run.hermes.empryo

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import run.hermes.empryo.engine.Chat
import run.hermes.empryo.engine.ChatItem
import run.hermes.empryo.engine.Diagnostics
import run.hermes.empryo.engine.EngineConfig
import run.hermes.empryo.engine.EngineState
import run.hermes.empryo.engine.Pairing
import run.hermes.empryo.engine.Paths
import run.hermes.empryo.engine.Phase
import run.hermes.empryo.engine.Thermal
import run.hermes.empryo.ui.AccountsScreen
import run.hermes.empryo.ui.ChatScreen
import run.hermes.empryo.ui.DiagnosticsScreen
import run.hermes.empryo.ui.EmpryoTheme
import run.hermes.empryo.ui.MainScreen
import run.hermes.empryo.ui.PairScreen
import run.hermes.empryo.ui.Screen
import run.hermes.empryo.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "EmpryoCI"
        const val EXTRA_SCREEN = "screen"
        val ciScreen = MutableStateFlow<Screen?>(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openScreen(intent)
        handleCiHooks(intent)
    }

    /** The pair-request notification opens the Pair screen. */
    private fun openScreen(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SCREEN)?.let { n -> Screen.entries.firstOrNull { it.name == n }?.let { ciScreen.value = it } }
    }

    /**
     * CI only (BuildConfig.CI_HOOKS, emulator build): `-e ci_guest '<sh>'` runs a command in the rootfs,
     * `--ez ci_toggle true|false` flips the lever, `--ez ci_diag true` runs diagnostics, `-e ci_prompt '<text>'`
     * sends a chat prompt (auto-allows permissions), `-e ci_screen <name>` opens a screen. Logs under tag EmpryoCI.
     */
    private fun handleCiHooks(intent: Intent?) {
        if (!BuildConfig.CI_HOOKS || intent == null) return
        val s = AppController.scope
        intent.getStringExtra("ci_screen")?.let { n -> Screen.entries.firstOrNull { it.name.equals(n, true) }?.let { ciScreen.value = it } }
        intent.getStringExtra("ci_guest")?.let { cmd ->
            val l2s = !intent.getBooleanExtra("ci_nol2s", false)
            s.launch {
                EngineState.status.first { it.phase != Phase.Installing && it.phase != Phase.NotInstalled }
                val r = AppController.runner.run(listOf("/bin/sh", "-c", cmd), 600, l2s)
                r.output.lines().forEach { Log.i(TAG, "guest: $it") }
                Log.i(TAG, "guest-exit: ${r.exitCode}")
            }
        }
        if (intent.hasExtra("ci_pair")) {
            val on = intent.getBooleanExtra("ci_pair", false)
            s.launch {
                if (on) {
                    val job = launch { Pairing.ui.collect { u -> Log.i(TAG, "pair: state=${u.state} code=${u.code != null} qr=${u.qr.size} reaches=${u.reaches} msg=${u.message}") } }
                    AppController.setPairing(true)
                    Pairing.setVisible(true)
                    val u = withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeoutOrNull(120_000) { Pairing.ui.first { it.code != null && it.qr.isNotEmpty() } } }
                    job.cancel()
                    Log.i(TAG, "pair-ready: ${u != null} link=${u?.link} reaches=${u?.reaches} qr=${u?.qr?.size} bridge=${EngineState.status.value.bridge}")
                } else AppController.setPairing(false)
            }
        }
        // Screenshot-only: a pair request the way phone-service sends it, then answered by the UI.
        intent.getStringExtra("ci_pair_request")?.let { name ->
            Pairing.onLine("""{"event":"confirm","id":"ci-fake","request":{"fingerprint":"428 913","name":"$name","via":"wifi","kind":"phone"}}""")?.let { Log.i(TAG, it) }
        }
        if (intent.hasExtra("ci_toggle")) {
            val on = intent.getBooleanExtra("ci_toggle", false)
            s.launch {
                EngineState.status.first { it.phase != Phase.Installing && it.phase != Phase.NotInstalled }
                AppController.toggle(on)
            }
        }
        if (intent.getBooleanExtra("ci_diag", false)) s.launch {
            Diagnostics.run(applicationContext)
            Log.i(TAG, "diag-done: ${Diagnostics.lastFile?.absolutePath}")
        }
        intent.getStringExtra("ci_prompt")?.let { prompt ->
            s.launch {
                val job = launch {
                    Chat.ui.collect { ui -> ui.permission?.let { p -> Log.i(TAG, "chat: permission ${p.title}"); Chat.answerPermission(p.options.firstOrNull { it.second == "allow_once" }?.first) } }
                }
                withContext(Dispatchers.IO) { Chat.send(prompt) }
                job.cancel()
                Chat.ui.value.items.forEach { item ->
                    when (item) {
                        is ChatItem.Agent -> Log.i(TAG, "chat: agent ${item.text.replace('\n', ' ')}")
                        is ChatItem.Tool -> Log.i(TAG, "chat: tool ${item.title} ${item.status} ${item.output.replace('\n', ' ')}")
                        is ChatItem.User -> Log.i(TAG, "chat: user ${item.text}")
                        is ChatItem.Note -> Log.i(TAG, "chat: note ${item.text}")
                    }
                }
                Log.i(TAG, "chat-done: error=${Chat.ui.value.error}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppController.ensureInstalled()
        openScreen(intent)
        if (BuildConfig.CI_HOOKS) AppController.scope.launch { EngineState.status.collect { Log.i(TAG, "phase: ${it.phase} ${it.installStage ?: ""} ${it.message ?: ""} bridge=${it.bridge}") } }
        handleCiHooks(intent)
        setContent {
            val ctx = LocalContext.current
            val settings by AppController.settings.state.collectAsStateWithLifecycle()
            val status by EngineState.status.collectAsStateWithLifecycle()
            EmpryoTheme(amoled = settings.amoled, dynamic = settings.dynamicColor) {
                var screen by rememberSaveable { mutableStateOf(Screen.Main) }
                val ci by ciScreen.collectAsStateWithLifecycle() // CI hooks and the pair-request notification
                LaunchedEffect(ci) { ci?.let { screen = it; ciScreen.value = null } }
                val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                // Heat chip: one reading every 10 s while the screen is visible (headroom returns NaN when asked faster).
                var thermal by androidx.compose.runtime.remember { mutableStateOf<Thermal.Reading?>(null) }
                LaunchedEffect(Unit) { while (true) { thermal = Thermal.read(ctx); delay(10_000) } }
                BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val forward = targetState != Screen.Main
                            val slide = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 500f)
                            (slideInHorizontally(slide) { w -> if (forward) w / 4 else -w / 4 } + fadeIn()) togetherWith
                                (slideOutHorizontally(slide) { w -> if (forward) -w / 4 else w / 4 } + fadeOut())
                        },
                        label = "screen",
                    ) { sc ->
                        val back = { screen = Screen.Main }
                        when (sc) {
                            Screen.Main -> MainScreen(
                                status, thermal, BuildConfig.EMPRYO_VERSION,
                                onNavigate = { screen = it },
                                onToggle = { AppController.toggle(it) },
                                onRetrySetup = { AppController.ensureInstalled() },
                                onCopy = { AppController.copy("Empryo Local", it) },
                            )
                            Screen.Settings -> SettingsScreen(
                                settings, status.isOn, onBack = back, onNavigate = { screen = it },
                                onUpdate = { AppController.settings.update(it) },
                                onShareLog = { Paths(ctx).engineLog.takeIf { it.isFile }?.let { AppController.shareFile(ctx, it, "Share engine log") } },
                            )
                            Screen.Accounts -> {
                                val login by AppController.login.collectAsStateWithLifecycle()
                                AccountsScreen(
                                    login, onBack = back,
                                    onLogin = { AppController.startLogin(ctx, it) },
                                    onLoginInput = { AppController.sendLoginInput(it) },
                                    onCancelLogin = { AppController.cancelLogin() },
                                    onOpen = { AppController.openUrl(ctx, it) },
                                    onCopy = { AppController.copy("Empryo sign-in", it) },
                                    onSetKey = { p, k -> AppController.setKey(p, k) },
                                    onListModels = { AppController.listModels() },
                                    defaultModel = settings.defaultModel,
                                    onSaveModel = { m ->
                                        AppController.settings.update { copy(defaultModel = m) }
                                        AppController.scope.launch(Dispatchers.IO) { runCatching { EngineConfig.apply(Paths(ctx), AppController.settings.value) } }
                                    },
                                )
                            }
                            Screen.Diagnostics -> {
                                val report by Diagnostics.report.collectAsStateWithLifecycle()
                                val running by Diagnostics.running.collectAsStateWithLifecycle()
                                DiagnosticsScreen(
                                    report, running, onBack = back,
                                    onRun = { AppController.scope.launch { Diagnostics.run(ctx.applicationContext) } },
                                    onCopy = { AppController.copy("Empryo diagnostics", it) },
                                    onShare = { Diagnostics.lastFile?.let { AppController.shareFile(ctx, it, "Share diagnostics") } },
                                )
                            }
                            Screen.Pair -> {
                                val pair by Pairing.ui.collectAsStateWithLifecycle()
                                PairScreen(
                                    pair, settings.pairOfficial, status.phase == Phase.Running, settings.lanAccess, onBack = back,
                                    onSetPairing = { AppController.setPairing(it) },
                                    onOpen = { Pairing.open(it) },
                                    onVisible = { Pairing.setVisible(it) },
                                    onAnswer = { Pairing.answer(it) },
                                    onCopy = { AppController.copy("Empryo pairing link", it) },
                                )
                            }
                            Screen.Chat -> {
                                val chat by Chat.ui.collectAsStateWithLifecycle()
                                ChatScreen(
                                    chat, status.phase == Phase.Running, onBack = back,
                                    onNew = { AppController.scope.launch(Dispatchers.IO) { Chat.newSession(Paths.WORKSPACE) } },
                                    onSend = { t -> AppController.scope.launch(Dispatchers.IO) { Chat.send(t) } },
                                    onStop = { Chat.cancel() },
                                    onPermission = { Chat.answerPermission(it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
