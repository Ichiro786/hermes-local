package dev.syed.hermeslocal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syed.hermeslocal.AppController
import dev.syed.hermeslocal.Screen
import dev.syed.hermeslocal.engine.EngineStatus
import dev.syed.hermeslocal.engine.Phase
import dev.syed.hermeslocal.engine.SettingsSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainScreen(status: EngineStatus, settings: SettingsSnapshot, onNavigate: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val installed = status.phase != Phase.NotInstalled && status.phase != Phase.Installing
    val canRun = installed && settings.passwordSet
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hermes Local", style = MaterialTheme.typography.headlineMediumEmphasized, fontWeight = FontWeight.Bold)
                StatusChip(status)
            }
            FilledTonalIconButton(onClick = { onNavigate(Screen.Providers) }, enabled = installed) {
                Icon(Icons.Rounded.Key, contentDescription = "Providers")
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = { onNavigate(Screen.Settings) }) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
            }
        }

        // First run: install + password cards take the lever's place, then hand it over.
        val setup = !installed || !settings.passwordSet
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(setup, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "setup") { isSetup ->
                if (isSetup) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
                        if (!installed) InstallCard(status) else PasswordCard(settings.username)
                    }
                } else {
                    ThrottleLever(
                        on = status.isOn,
                        mode = when (status.phase) {
                            Phase.Starting -> LeverMode.Starting
                            Phase.Running -> LeverMode.Running
                            Phase.Stopping -> LeverMode.Stopping
                            Phase.Failed -> LeverMode.Failed
                            else -> LeverMode.Off
                        },
                        enabled = canRun && status.phase != Phase.Stopping,
                        onToggle = { AppController.toggle(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val hint = when {
            !installed -> "Installing the engine…"
            !settings.passwordSet -> "Set a dashboard password to unlock the lever"
            status.phase == Phase.Running -> "Pull down to stop"
            status.phase == Phase.Starting -> "Spooling up…"
            status.phase == Phase.Stopping -> "Spooling down…"
            else -> "Push up to start"
        }
        AnimatedContent(hint, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "hint") {
            Text(it.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(12.dp))
        StatusCard(status, onOpenHermes = { AppController.openHermes(ctx) }, onOpenDashboard = { AppController.openUrl(ctx, status.localUrl) })
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatusChip(status: EngineStatus) {
    val (label, color) = when (status.phase) {
        Phase.Running -> "Running" to MaterialTheme.colorScheme.primary
        Phase.Starting -> "Starting" to StatusColors.Amber
        Phase.Stopping -> "Stopping" to StatusColors.Amber
        Phase.Failed -> "Stopped with an error" to StatusColors.Red
        Phase.Installing -> "Installing engine" to StatusColors.Amber
        Phase.NotInstalled -> "Engine not installed" to MaterialTheme.colorScheme.outline
        Phase.Stopped -> "Off" to MaterialTheme.colorScheme.outline
    }
    val c by animateColorAsState(color, label = "chip")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(8.dp))
        AnimatedContent(label, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "chipText") {
            Text(it, style = MaterialTheme.typography.labelLarge, color = c)
        }
    }
}

@Composable
private fun StatusCard(status: EngineStatus, onOpenHermes: () -> Unit, onOpenDashboard: () -> Unit) {
    val running = status.phase == Phase.Running
    Card(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("UPTIME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Uptime(status.runningSince)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("PORT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${status.port}", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    status.localUrl, Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { AppController.copy("Hermes URL", status.localUrl) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy URL")
                }
            }
            if (status.lan && running) {
                AppController.lanAddresses().firstOrNull()?.let {
                    Text("LAN: http://$it:${status.port}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            status.message?.let {
                Text(it, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = if (status.phase == Phase.Failed) StatusColors.Red else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            val client = remember(running) { AppController.installedClient() }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onOpenHermes, enabled = running, modifier = Modifier.weight(1f).height(56.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (client != null) "Open Hermes" else "Open dashboard", maxLines = 1)
                }
                if (client != null) {
                    FilledTonalButton(onClick = onOpenDashboard, enabled = running, modifier = Modifier.height(56.dp), shapes = ButtonDefaults.shapes()) {
                        Text("Dashboard")
                    }
                }
            }
        }
    }
}

@Composable
private fun Uptime(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (since > 0) { now = System.currentTimeMillis(); delay(1000) }
    }
    val text = if (since <= 0) "—" else {
        val s = ((now - since) / 1000).coerceAtLeast(0)
        if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }
    Text(text, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
}

@Composable
private fun InstallCard(status: EngineStatus) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (status.phase == Phase.Installing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(status.installStage ?: "Installing", style = MaterialTheme.typography.titleMedium)
                        Text("One-time setup: Hermes Agent, Codex + Antigravity sign-in tools", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp))
                val f = status.installProgress
                if (f >= 0f) LinearWavyProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text("Engine not installed", style = MaterialTheme.typography.titleMedium)
                status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { AppController.ensureInstalled() }, shapes = ButtonDefaults.shapes()) { Text("Install") }
            }
        }
    }
}

@Composable
private fun PasswordCard(defaultUser: String) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(defaultUser) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Protect your dashboard", style = MaterialTheme.typography.titleMedium)
            Text("Hermes clients sign in with this. It stays on this phone (stored as a scrypt hash).", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(user, { user = it.trim() }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                pw, { pw = it }, label = { Text("Password (6+ characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                pw2, { pw2 = it }, label = { Text("Confirm password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = pw2.isNotEmpty() && pw2 != pw,
            )
            error?.let { Text(it, color = StatusColors.Red, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    busy = true; error = null
                    scope.launch { error = AppController.setPassword(user.ifBlank { "admin" }, pw); busy = false }
                },
                enabled = !busy && pw.length >= 6 && pw == pw2 && user.isNotBlank(), shapes = ButtonDefaults.shapes(),
            ) {
                if (busy) LoadingIndicator(Modifier.size(24.dp)) else Text("Save password")
            }
        }
    }
}
