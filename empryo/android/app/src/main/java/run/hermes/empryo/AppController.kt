package run.hermes.empryo

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.hermes.empryo.engine.EngineService
import run.hermes.empryo.engine.EngineState
import run.hermes.empryo.engine.Installer
import run.hermes.empryo.engine.Paths
import run.hermes.empryo.engine.Phase
import run.hermes.empryo.engine.Runner
import run.hermes.empryo.engine.Settings
import java.io.File

/** A running `empryo --login <account>`: its output, the link/code it printed, and whether it is done. */
data class LoginUi(
    val account: String = "",
    val lines: List<String> = emptyList(),
    val url: String? = null,
    val code: String? = null,
    val running: Boolean = false,
    val exitCode: Int? = null,
)

/** App-wide actions shared by the screens. Long work runs in an app-lifetime scope. */
@SuppressLint("StaticFieldLeak")
object AppController {
    private lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings get() = Settings.get(app)
    val paths get() = Paths(app)
    val runner get() = Runner(app)

    fun init(context: Context) { app = context.applicationContext }

    fun ensureInstalled() {
        if (EngineState.status.value.phase == Phase.Installing) return
        val installer = Installer(app)
        val needRootfs = !installer.rootfsCurrent()
        val needEngine = !paths.engineInstalled()
        if (!needRootfs && !needEngine) {
            if (EngineState.status.value.phase == Phase.NotInstalled) EngineState.set { it.copy(phase = Phase.Stopped, message = null) }
            return
        }
        if (EngineState.status.value.isOn) EngineService.stop(app)
        EngineState.set { it.copy(phase = Phase.Installing, installStage = "Preparing", installProgress = -1f, message = null) }
        scope.launch {
            try {
                val progress: (String, Float) -> Unit = { stage, f -> EngineState.set { it.copy(installStage = stage, installProgress = f) } }
                if (needRootfs) installer.installRootfs(progress)
                if (needEngine) installer.installEngine(progress)
                EngineState.set { it.copy(phase = Phase.Stopped, installStage = null, installProgress = -1f, message = null) }
            } catch (e: Throwable) {
                EngineState.set { it.copy(phase = Phase.NotInstalled, installStage = null, message = "Setup failed: ${e.message ?: e}") }
            }
        }
    }

    fun toggle(on: Boolean) = if (on) EngineService.start(app) else EngineService.stop(app)

    fun copy(label: String, text: String) {
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    fun shareFile(context: Context, file: File, title: String) {
        val dir = File(app.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, file.name)
        file.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", out)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                title,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---- model providers (empryo --login / --set-key)

    private val _login = MutableStateFlow(LoginUi())
    val login: StateFlow<LoginUi> = _login.asStateFlow()
    private var loginSession: Runner.Session? = null
    private val urlRe = Regex("""https?://[^\s"'<>]+""")
    private val codeRe = Regex("""(?i)(?:your code|enter code|code)[:\s]+([A-Z0-9]{4}-[A-Z0-9]{4,6})""")
    private val ansi = Regex("""\u001B\[[0-9;?]*[A-Za-z]|\u001B\][^\u0007]*\u0007""")

    /** Runs `empryo --login <account> --no-browser` and opens the first link it prints in the browser. */
    fun startLogin(context: Context, account: String) {
        cancelLogin()
        _login.value = LoginUi(account = account, running = true)
        var opened = false
        loginSession = runCatching {
            runner.startSession(listOf(Paths.EMPRYO, "--login", account, "--no-browser"), onLine = line@{ raw ->
                val line = ansi.replace(raw, "").trim()
                if (line.isEmpty()) return@line
                val url = urlRe.find(line)?.value?.trimEnd('.', ',', ')')
                val code = codeRe.find(line)?.groupValues?.get(1)
                _login.update { it.copy(lines = (it.lines + line).takeLast(40), url = url ?: it.url, code = code ?: it.code) }
                if (url != null && !opened) { opened = true; scope.launch { openUrl(context, url) } }
            }, onExit = { code -> _login.update { it.copy(running = false, exitCode = code) } })
        }.getOrElse { e -> _login.update { it.copy(running = false, exitCode = -1, lines = listOf(e.message ?: e.toString())) }; null }
    }

    /** Paste-back for flows that ask for a code on stdin. */
    fun sendLoginInput(text: String) { loginSession?.send(text.trim() + "\n") }

    fun cancelLogin() { loginSession?.let { s -> scope.launch(Dispatchers.IO) { s.cancel() } }; loginSession = null }

    /** `empryo --set-key <provider> <key>`; the key never reaches the log or the screen. */
    suspend fun setKey(provider: String, key: String): String {
        val r = runner.run(listOf(Paths.EMPRYO, "--set-key", provider.trim(), key.trim()), 60)
        return ansi.replace(r.output, "").replace(key.trim(), "•••").trim().lines().lastOrNull { it.isNotBlank() } ?: "exit ${r.exitCode}"
    }

    suspend fun listModels(): String = ansi.replace(runner.run(listOf(Paths.EMPRYO, "--list-models"), 90).output, "").trim()
}
