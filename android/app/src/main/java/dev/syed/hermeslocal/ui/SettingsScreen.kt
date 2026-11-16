package dev.syed.hermeslocal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.syed.hermeslocal.AppController
import dev.syed.hermeslocal.BuildConfig
import dev.syed.hermeslocal.Screen
import dev.syed.hermeslocal.engine.EngineService
import dev.syed.hermeslocal.engine.EngineStatus
import dev.syed.hermeslocal.engine.Phase
import dev.syed.hermeslocal.engine.SettingsSnapshot
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(status: EngineStatus, settings: SettingsSnapshot, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val s = AppController.settings
    var dialog by remember { mutableStateOf<String?>(null) }
    val installed = status.phase != Phase.NotInstalled && status.phase != Phase.Installing
    val busy = status.phase == Phase.Installing
    fun changed(block: SettingsSnapshot.() -> SettingsSnapshot) { s.update(block); EngineService.restart(ctx) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { AppController.installFromFile(it) } }

    ScreenFrame("Settings", onBack) {
        if (status.isOn) Text(
            "Server changes restart Hermes.", Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionChip(Icons.Rounded.Dns, "Server")
        Group {
            NavRow(Icons.Rounded.Monitor, "Dashboard port", "${settings.port}") { dialog = "port" }
            GroupDivider()
            SwitchRow(
                Icons.Rounded.Hub, "LAN access",
                if (settings.lanAccess) "Other devices on your Wi-Fi can reach Hermes (password required)" else "Only apps on this phone (127.0.0.1)",
                settings.lanAccess,
            ) { v -> changed { copy(lanAccess = v) } }
            GroupDivider()
            SwitchRow(Icons.Rounded.Code, "OpenAI-compatible API server", "Hermes gateway on port ${settings.apiPort} with a bearer key", settings.apiServer) { v ->
                changed { copy(apiServer = v) }
            }
            if (settings.apiServer) {
                GroupDivider()
                NavRow(Icons.Rounded.Monitor, "API port", "${settings.apiPort}") { dialog = "apiport" }
                GroupDivider()
                val key = AppController.apiKey()
                NavRow(Icons.Rounded.VpnKey, "Copy API key", if (key != null) "${key.take(6)}…" else "Created on the next start", enabled = key != null, chevron = false) {
                    key?.let { AppController.copy("Hermes API key", it) }
                }
            }
        }

        SectionChip(Icons.Rounded.Shield, "Security")
        Group {
            NavRow(Icons.Rounded.Key, "Dashboard login", "User ${settings.username} · ${if (settings.passwordSet) "password set" else "no password"}", enabled = installed) { dialog = "password" }
            GroupDivider()
            NavRow(Icons.Rounded.ViewInAr, "Model providers", "Codex and Antigravity sign-in · voice keys", enabled = installed) { onNavigate(Screen.Providers) }
        }

        SectionChip(Icons.Rounded.Bolt, "Power")
        Group {
            SwitchRow(Icons.Rounded.PowerSettingsNew, "Start on boot", "Turn Hermes on when the phone starts", settings.startOnBoot) { v -> s.update { copy(startOnBoot = v) } }
            GroupDivider()
            SwitchRow(
                Icons.Rounded.Bedtime, "Keep awake always",
                if (settings.keepAwake) "Holds a wake lock the whole time Hermes runs (uses more battery)"
                else "Off: awake only during a reply, a due cron job or LAN access",
                settings.keepAwake,
            ) { v -> changed { copy(keepAwake = v) } }
            GroupDivider()
            SwitchRow(Icons.Rounded.RestartAlt, "Restart after a crash", "Up to 3 tries", settings.autoRestart) { v -> s.update { copy(autoRestart = v) } }
            GroupDivider()
            val optimized = remember(status.phase) { AppController.batteryOptimized() }
            NavRow(
                Icons.Rounded.BatterySaver, "Battery optimisation",
                if (optimized) "On: ColorOS may freeze Hermes in the background. Tap to exempt." else "Exempt: Hermes can run in the background",
                enabled = optimized,
            ) { AppController.requestBatteryExemption(ctx) }
        }

        SectionChip(Icons.Rounded.Palette, "Appearance")
        Group {
            SwitchRow(Icons.Rounded.Contrast, "Theme: AMOLED black", if (settings.amoled) "Pure black background, cards slightly lifted" else "Off: dark", settings.amoled) { v ->
                s.update { copy(amoled = v) }
            }
            GroupDivider()
            val canDynamic = android.os.Build.VERSION.SDK_INT >= 31
            SwitchRow(
                Icons.Rounded.Wallpaper, "Wallpaper colours (Material You)",
                if (!canDynamic) "Needs Android 12 or newer" else if (settings.dynamicColor) "Colours follow your wallpaper" else "Off: lavender",
                settings.dynamicColor && canDynamic, enabled = canDynamic,
            ) { v -> s.update { copy(dynamicColor = v) } }
        }

        SectionChip(Icons.Rounded.Memory, "Engine")
        Group {
            val v = remember(status.phase) { AppController.engineVersion() }
            NavRow(
                Icons.Rounded.Memory, v["hermes_version"]?.substringBefore(" (") ?: "Not installed",
                "agy ${v["agy"]?.substringAfterLast(' ') ?: "—"} · ${v["arch"] ?: BuildConfig.ENGINE_ABI} · rev ${v["revision"] ?: "—"} · ${BuildConfig.ENGINE_TAG}",
                chevron = false,
            ) {}
            GroupDivider()
            NavRow(Icons.Rounded.SdStorage, "Storage", "What uses space · clean up · reset") { onNavigate(Screen.Storage) }
            GroupDivider()
            NavRow(Icons.AutoMirrored.Rounded.Article, "Logs", "Live output, share") { onNavigate(Screen.Logs) }
            GroupDivider()
            NavRow(Icons.Rounded.Download, "Update engine", "Download an engine release (keeps your data)", enabled = !busy) { dialog = "update" }
            GroupDivider()
            NavRow(Icons.Rounded.FileOpen, "Install from file", "Offline: a rootfs-*.tar.zst you downloaded (keeps your data)", enabled = !busy) {
                pickFile.launch(arrayOf("*/*"))
            }
        }
        Text(
            "Hermes Local ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · runs Hermes Agent by Nous Research",
            Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
    }

    when (dialog) {
        "port" -> NumberDialog("Dashboard port", settings.port, { dialog = null }) { p -> changed { copy(port = p) } }
        "apiport" -> NumberDialog("API port", settings.apiPort, { dialog = null }) { p -> changed { copy(apiPort = p) } }
        "password" -> PasswordDialog(settings.username) { dialog = null }
        "update" -> UrlDialog(settings.engineUrl.ifBlank { AppController.defaultEngineUrl() }, { dialog = null }) { url ->
            s.update { copy(engineUrl = url) }; AppController.updateFromUrl(url)
        }
    }
}

@Composable
private fun NumberDialog(title: String, value: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    val n = text.toIntOrNull()
    val ok = n != null && n in 1024..65535
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit).take(5) }, singleLine = true, isError = !ok,
                supportingText = { Text("1024–65535") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = { TextButton(onClick = { onSave(n!!); onDismiss() }, enabled = ok) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UrlDialog(value: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Update engine") },
        text = {
            Column {
                Text("Engine payload URL (.tar.zst or .tar.xz with a matching .sha256 next to it).", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it.trim() }, singleLine = false, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text); onDismiss() }, enabled = text.startsWith("https://")) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(username: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(username) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Dashboard login") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it.trim() }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(pw, { pw = it }, label = { Text("New password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(pw2, { pw2 = it }, label = { Text("Confirm") }, singleLine = true, isError = pw2.isNotEmpty() && pw2 != pw,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text("Signed-in clients are signed out and must log in again.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        val e = AppController.setPassword(user.ifBlank { "admin" }, pw)
                        busy = false
                        if (e == null) onDismiss() else error = e
                    }
                },
                enabled = !busy && pw.length >= 6 && pw == pw2, shapes = ButtonDefaults.shapes(),
            ) { if (busy) LoadingIndicator(Modifier.height(20.dp)) else Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
