package dev.syed.hermeslocal

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import dev.syed.hermeslocal.engine.CommandRunner
import dev.syed.hermeslocal.engine.EngineInstaller
import dev.syed.hermeslocal.engine.EnginePaths
import dev.syed.hermeslocal.engine.EngineService
import dev.syed.hermeslocal.engine.EngineState
import dev.syed.hermeslocal.engine.Phase
import dev.syed.hermeslocal.engine.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** App-wide actions shared by the screens. Long work runs in an app-lifetime scope, not a screen's. */
@SuppressLint("StaticFieldLeak")
object AppController {
    private lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings get() = Settings.get(app)
    val paths get() = EnginePaths(app)
    val installer get() = EngineInstaller(app)
    val runner get() = CommandRunner(app)

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Hermes clients that can connect to the local dashboard, preferred first. */
    val HERMES_CLIENTS = listOf(
        "ai.nousresearch.hermes.mobile" to "Hermes (official)",
        "dev.syed.hermes" to "Hermes (Syed)",
        "dev.avifenesh.hermes" to "Hermes Android",
        "com.m57.hermescontrol" to "Hermes Mobile",
    )

    fun installedClient(): Pair<String, String>? =
        HERMES_CLIENTS.firstOrNull { (pkg, _) -> app.packageManager.getLaunchIntentForPackage(pkg) != null }

    fun ensureInstalled() {
        val s = EngineState.status.value
        if (s.phase == Phase.Installing) return
        if (paths.isInstalled() && !installer.bundledDiffersFromInstalled()) {
            if (s.phase == Phase.NotInstalled) EngineState.set { it.copy(phase = Phase.Stopped) }
            return
        }
        if (!installer.bundledAvailable()) {
            if (!paths.isInstalled()) EngineState.set {
                it.copy(phase = Phase.NotInstalled, message = "This build has no bundled engine. Use Settings › Update engine.")
            }
            return
        }
        install { onProgress -> installer.installBundled(onProgress) }
    }

    fun reinstallBundled() = install { onProgress -> installer.installBundled(onProgress) }

    fun updateFromUrl(url: String) = install { onProgress -> installer.installFromUrl(url, onProgress) }

    private fun install(block: suspend (onProgress: (String, Float) -> Unit) -> Unit) {
        if (EngineState.status.value.isOn) EngineService.stop(app)
        EngineState.set { it.copy(phase = Phase.Installing, installStage = "Preparing", installProgress = -1f, message = null) }
        scope.launch {
            try {
                block { stage, f -> EngineState.set { it.copy(installStage = stage, installProgress = f) } }
                // The password lives in the engine's config; a fresh engine (not an update) has none.
                val hasPw = runner.run(listOf("/bin/bash", "/opt/hermes-local/bin/hermes-local-passwd", "--check"), timeoutSec = 90).exitCode == 0
                settings.update { copy(passwordSet = hasPw) }
                EngineState.set { it.copy(phase = Phase.Stopped, installStage = null, installProgress = -1f, message = null) }
            } catch (e: Throwable) {
                EngineState.set {
                    it.copy(
                        phase = if (paths.isInstalled()) Phase.Failed else Phase.NotInstalled,
                        installStage = null, message = "Engine install failed: ${e.message}",
                    )
                }
            }
        }
    }

    fun reset() {
        EngineService.stop(app)
        scope.launch(Dispatchers.IO) {
            installer.reset()
            settings.update { copy(passwordSet = false) }
            EngineState.set { it.copy(phase = Phase.NotInstalled, message = null) }
        }
    }

    suspend fun setPassword(username: String, password: String): String? {
        val r = runner.run(listOf("/bin/bash", "/opt/hermes-local/bin/hermes-local-passwd", username), stdin = password + "\n", timeoutSec = 120)
        if (r.exitCode != 0) return r.output.lines().lastOrNull { it.isNotBlank() } ?: "Could not set the password (${r.exitCode})"
        settings.update { copy(username = username, passwordSet = true) }
        EngineService.restart(app) // a running dashboard picks up the new credentials
        return null
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

    /** Opens the Hermes mobile app when installed (the dashboard URL is copied for its connect screen), else the dashboard. */
    fun openHermes(context: Context) {
        val url = EngineState.status.value.localUrl
        val client = installedClient()
        if (client != null) {
            copy("Hermes URL", url)
            app.packageManager.getLaunchIntentForPackage(client.first)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
            }
        }
        openUrl(context, url)
    }

    fun batteryOptimized(): Boolean =
        !(app.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(app.packageName)

    @SuppressLint("BatteryLife")
    fun requestBatteryExemption(context: Context) {
        val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))
        try {
            context.startActivity(direct)
        } catch (_: Exception) {
            runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun shareLog(context: Context) {
        val src = paths.engineLog
        val dir = File(app.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "hermes-local.log")
        out.writeText((paths.engineLogOld.takeIf { it.exists() }?.readText() ?: "") + (src.takeIf { it.exists() }?.readText() ?: "(empty)"))
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", out)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Share Hermes log",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun engineVersion(): Map<String, String> = runCatching {
        paths.engineVersion.readLines().mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }.getOrDefault(emptyMap())

    fun apiKey(): String? = runCatching { paths.apiKey.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun defaultEngineUrl(): String {
        val abi = if (BuildConfig.ENGINE_ABI == "x86_64") "x86_64" else "arm64"
        return "https://github.com/${BuildConfig.ENGINE_REPO}/releases/download/${BuildConfig.ENGINE_TAG}/rootfs-$abi.tar.xz"
    }
}
