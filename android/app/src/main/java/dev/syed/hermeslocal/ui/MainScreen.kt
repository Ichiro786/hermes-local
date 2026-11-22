package dev.syed.hermeslocal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hermes Local", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                StatusLine(status)
            }
            RoundIcon(Icons.Rounded.Key, "Providers", enabled = installed) { onNavigate(Screen.Providers) }
            Spacer(Modifier.width(10.dp))
            RoundIcon(Icons.Rounded.Settings, "Settings") { onNavigate(Screen.Settings) }
        }

        // First run: download + password cards take the lever's place, then hand it over.
        val setup = !installed || !settings.passwordSet
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth * 0.92f, maxHeight)
            AnimatedContent(setup, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "setup") { isSetup ->
                if (isSetup) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.Center) {
                        if (!installed) InstallCard(status) else PasswordCard(settings.username)
                    }
                } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // The M3 Expressive cookie behind the lever takes the engine's colour (green running, amber busy).
                    val blob by animateColorAsState(
                        when (status.phase) {
                            Phase.Running -> MaterialTheme.status.onBlob
                            Phase.Starting, Phase.Stopping -> MaterialTheme.status.busyBlob
                            Phase.Failed -> MaterialTheme.status.failedBlob
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        tween(450), label = "blob",
                    )
                    Box(Modifier.size(side).background(blob, MaterialShapes.Cookie4Sided.toShape()), contentAlignment = Alignment.Center) {
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
                            modifier = Modifier.fillMaxHeight(0.9f).fillMaxWidth(0.78f),
                        )
                    }
                }
            }
        }
        val hint = when {
            status.phase == Phase.Installing -> "Setting up the engine…"
            !installed -> "The engine is needed to start"
            !settings.passwordSet -> "Set a dashboard password to unlock the lever"
            status.phase == Phase.Running -> "Slide down to stop"
            status.phase == Phase.Starting -> "Starting…"
            status.phase == Phase.Stopping -> "Stopping…"
            else -> "Slide up to start"
        }
        AnimatedContent(hint, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "hint") {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(16.dp))
        val wakeConnected by dev.syed.hermeslocal.wake.AutoWake.connected.collectAsStateWithLifecycle()
        val autoWake = when {
            !settings.autoWake || !canRun -> null
            wakeConnected -> true
            else -> false
        }
        DashboardCard(status, autoWake, onOpenHermes = { AppController.openHermes(ctx) }, onOpenDashboard = { AppController.openUrl(ctx, status.localUrl) })
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) { Icon(icon, contentDescription = label) }
}

@Composable
private fun StatusLine(status: EngineStatus) {
    val (label, color) = when (status.phase) {
        Phase.Running -> "Running" to MaterialTheme.status.on
        Phase.Starting -> "Starting" to MaterialTheme.status.busy
        Phase.Stopping -> "Stopping" to MaterialTheme.status.busy
        Phase.Failed -> "Stopped with an error" to MaterialTheme.status.failed
        Phase.Installing -> "Setting up" to MaterialTheme.status.busy
        Phase.NotInstalled -> "Engine not installed" to MaterialTheme.colorScheme.outline
        Phase.Stopped -> "Server off" to MaterialTheme.colorScheme.outline
    }
    val c by animateColorAsState(color, label = "dot")
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(10.dp))
        AnimatedContent(label, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "statusText") {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = if (status.phase == Phase.Running) c else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DashboardCard(status: EngineStatus, autoWake: Boolean?, onOpenHermes: () -> Unit, onOpenDashboard: () -> Unit) {
    val running = status.phase == Phase.Running
    val container by animateColorAsState(if (running) MaterialTheme.status.onCard else MaterialTheme.colorScheme.surfaceContainer, tween(450), label = "card")
    Card(
        Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Monitor, null, Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Dashboard", style = MaterialTheme.typography.titleLarge)
                    Text("Local server control", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedContent(running, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stat") { r ->
                    if (r) Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.MonitorHeart, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text("Uptime", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Uptime(status.runningSince)
                    } else Column(
                        Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Port", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${status.port}", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            val chips = buildList {
                if (status.thermal >= 1) add(Triple(Icons.Rounded.Thermostat, thermalLabel(status.thermal), if (status.thermal >= 2) MaterialTheme.status.busy else null))
                if (running) add(Triple(if (status.awake != null) Icons.Rounded.Bolt else Icons.Rounded.Bedtime, status.awake ?: "CPU may sleep", null))
                // null: auto-wake off; false: on but Android has not bound the service (Settings explains why).
                if (autoWake == true) add(Triple(Icons.Rounded.AutoMode, "Auto-wake with Hermes", null))
                if (autoWake == false) add(Triple(Icons.Rounded.AutoMode, "Auto-wake needs access", MaterialTheme.status.busy))
            }
            if (chips.isNotEmpty()) FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { (icon, text, tint) -> Chip(icon, text, tint) }
            }
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)).padding(start = 18.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    status.localUrl, Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { AppController.copy("Hermes URL", status.localUrl) }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy URL") }
            }
            if (status.lan && running) {
                AppController.lanAddresses().firstOrNull()?.let {
                    Text("LAN: http://$it:${status.port}", Modifier.padding(top = 8.dp, start = 4.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            status.message?.takeIf { status.phase != Phase.NotInstalled && status.phase != Phase.Installing }?.let {
                Text(it, Modifier.padding(top = 10.dp, start = 4.dp), style = MaterialTheme.typography.bodyMedium,
                    color = if (status.phase == Phase.Failed) MaterialTheme.status.failed else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
            val client = remember(running) { AppController.installedClient() }
            // Side by side when both labels fit, stacked at large font / display sizes.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = client != null && maxWidth < 300.dp * LocalDensity.current.fontScale
                val open = @Composable { m: Modifier ->
                    Button(onClick = onOpenHermes, enabled = running, modifier = m.heightIn(min = 60.dp), shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (client != null) "Open Hermes" else "Open dashboard", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                    }
                }
                val dash = @Composable { m: Modifier ->
                    FilledTonalButton(onClick = onOpenDashboard, enabled = running, modifier = m.heightIn(min = 60.dp), shapes = ButtonDefaults.shapes()) {
                        Text("Dashboard", maxLines = 1, softWrap = false)
                    }
                }
                when {
                    client == null -> open(Modifier.fillMaxWidth())
                    stacked -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { open(Modifier.fillMaxWidth()); dash(Modifier.fillMaxWidth()) }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { open(Modifier.weight(1f)); dash(Modifier) }
                }
            }
        }
    }
}

private fun thermalLabel(s: Int) = when (s) { 1 -> "Warm"; 2 -> "Hot · slowed down"; 3 -> "Very hot"; else -> "Too hot" }

@Composable
private fun Chip(icon: ImageVector, text: String, tint: Color?) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        if (s >= 3600) "%dh %02dm".format(s / 3600, s / 60 % 60) else "%dm %02ds".format(s / 60, s % 60)
    }
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.status.on, fontWeight = FontWeight.Medium)
}

@Composable
private fun InstallCard(status: EngineStatus) {
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { AppController.installFromFile(it) } }
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(22.dp)) {
            if (status.phase == Phase.Installing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        val pct = status.installProgress.takeIf { it >= 0f }?.let { " · ${(it * 100).toInt()} %" } ?: ""
                        Text((status.installStage ?: "Setting up") + pct, style = MaterialTheme.typography.titleMedium)
                        Text("One-time: Hermes Agent with Codex and Antigravity sign-in (about 500 MB download, resumes if interrupted)",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                val f = status.installProgress
                if (f >= 0f) {
                    val shown by animateFloatAsState(f, label = "progress")
                    LinearWavyProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth())
                } else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text("Engine not installed", style = MaterialTheme.typography.titleMedium)
                status.message?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.status.failed) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { AppController.downloadEngine() }, shapes = ButtonDefaults.shapes()) { Text(if (status.message != null) "Retry download" else "Download") }
                    FilledTonalButton(onClick = { pickFile.launch(arrayOf("*/*")) }, shapes = ButtonDefaults.shapes()) { Text("Install from file") }
                }
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
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            error?.let { Text(it, color = MaterialTheme.status.failed, style = MaterialTheme.typography.bodySmall) }
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
