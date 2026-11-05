package dev.syed.hermeslocal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title.uppercase(), Modifier.padding(start = 8.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Card(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) { Column(Modifier.padding(vertical = 6.dp), content = content) }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun ActionRow(title: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsScreen(status: EngineStatus, settings: SettingsSnapshot, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val s = AppController.settings
    var dialog by remember { mutableStateOf<String?>(null) }
    val restartNote = if (status.isOn) "Applies on the next start (Hermes restarts)" else null
    fun changed(block: SettingsSnapshot.() -> SettingsSnapshot) { s.update(block); EngineService.restart(ctx) }

    ScreenFrame("Settings", onBack) {
        Section("Server") {
            ActionRow("Dashboard port", "${settings.port}${restartNote?.let { " · $it" } ?: ""}") { dialog = "port" }
            SwitchRow(
                "LAN access", if (settings.lanAccess) "Other devices on your Wi-Fi can reach Hermes (password required)" else "Only apps on this phone (127.0.0.1)",
                settings.lanAccess,
            ) { v -> changed { copy(lanAccess = v) } }
            SwitchRow("OpenAI-compatible API server", "Hermes gateway on port ${settings.apiPort} with a bearer key", settings.apiServer) { v -> changed { copy(apiServer = v) } }
            if (settings.apiServer) {
                ActionRow("API port", "${settings.apiPort}") { dialog = "apiport" }
                val key = AppController.apiKey()
                ActionRow("Copy API key", if (key != null) "${key.take(6)}…" else "Created on the next start", enabled = key != null) {
                    key?.let { AppController.copy("Hermes API key", it) }
                }
            }
        }
        Section("Security") {
            ActionRow("Dashboard login", "User ${settings.username} · ${if (settings.passwordSet) "password set" else "no password"}", enabled = status.phase != Phase.NotInstalled && status.phase != Phase.Installing) { dialog = "password" }
            ActionRow("Model providers", "Codex and Antigravity sign-in") { onNavigate(Screen.Providers) }
        }
        Section("Power") {
            SwitchRow("Start on boot", "Turn Hermes on when the phone starts", settings.startOnBoot) { v -> s.update { copy(startOnBoot = v) } }
            SwitchRow("Keep awake while running", "Holds a partial wake lock so Hermes keeps working with the screen off", settings.keepAwake) { v -> changed { copy(keepAwake = v) } }
            SwitchRow("Restart after a crash", "Up to 3 tries", settings.autoRestart) { v -> s.update { copy(autoRestart = v) } }
            val optimized = remember(status.phase) { AppController.batteryOptimized() }
            ActionRow(
                "Battery optimisation", if (optimized) "On: ColorOS may freeze Hermes in the background. Tap to exempt." else "Exempt: Hermes can run in the background",
                enabled = optimized,
            ) { AppController.requestBatteryExemption(ctx) }
        }
        Section("Appearance") {
            SwitchRow("AMOLED black", "Pure black background in dark mode", settings.amoled) { v -> s.update { copy(amoled = v) } }
            SwitchRow("Dynamic colour", "Match your wallpaper (Android 12+)", settings.dynamicColor) { v -> s.update { copy(dynamicColor = v) } }
        }
        Section("Engine") {
            val v = remember(status.phase) { AppController.engineVersion() }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(v["hermes_version"] ?: "Not installed", style = MaterialTheme.typography.titleMedium)
                Text(
                    "agy ${v["agy"] ?: "—"} · Ubuntu ${v["ubuntu_base"] ?: "—"} · ${v["arch"] ?: BuildConfig.ENGINE_ABI} · rev ${v["revision"] ?: "—"}",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ActionRow("Logs", "Live output, share") { onNavigate(Screen.Logs) }
            ActionRow("Update engine", "Download a newer engine (keeps your data)", enabled = status.phase != Phase.Installing) { dialog = "update" }
            ActionRow("Reinstall bundled engine", "Repairs a broken engine (keeps your data)", enabled = status.phase != Phase.Installing && AppController.installer.bundledAvailable()) {
                dialog = "reinstall"
            }
            ActionRow("Reset", "Deletes the engine, Hermes settings, logins and sessions", enabled = status.phase != Phase.Installing) { dialog = "reset" }
        }
        Text(
            "Hermes Local ${BuildConfig.VERSION_NAME} · runs Hermes Agent by Nous Research",
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
        "reinstall" -> ConfirmDialog("Reinstall the bundled engine?", "Hermes stops while it reinstalls. Your settings, logins and sessions stay.", "Reinstall", { dialog = null }) {
            AppController.reinstallBundled()
        }
        "reset" -> ConfirmDialog("Reset everything?", "This deletes the engine and all Hermes data on this phone: config, provider logins, sessions and the dashboard password.", "Reset", { dialog = null }) {
            AppController.reset()
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
                Text("Engine payload URL (a .tar.xz with a matching .sha256 next to it).", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it.trim() }, singleLine = false, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text); onDismiss() }, enabled = text.startsWith("https://")) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
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
                error?.let { Text(it, color = StatusColors.Red, style = MaterialTheme.typography.bodySmall) }
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
