package run.hermes.empryo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Wifi
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
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import run.hermes.empryo.engine.EngineStatus
import run.hermes.empryo.engine.Phase
import run.hermes.empryo.engine.Thermal

enum class Screen { Main, Settings, Accounts, Diagnostics, Chat }

@Composable
fun MainScreen(
    status: EngineStatus,
    thermal: Thermal.Reading?,
    engineVersion: String,
    onNavigate: (Screen) -> Unit,
    onToggle: (Boolean) -> Unit,
    onRetrySetup: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val installed = status.phase != Phase.NotInstalled && status.phase != Phase.Installing
    val running = status.phase == Phase.Running
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Empryo Local", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                StatusLine(status)
            }
            RoundIcon(Icons.Rounded.Key, "Model providers", enabled = installed) { onNavigate(Screen.Accounts) }
            Spacer(Modifier.width(10.dp))
            RoundIcon(Icons.Rounded.Settings, "Settings") { onNavigate(Screen.Settings) }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth * 0.92f, maxHeight)
            AnimatedContent(installed, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "setup") { ready ->
                if (!ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SetupCard(status, onRetrySetup) }
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val blob by animateColorAsState(
                        when (status.phase) {
                            Phase.Running -> StatusColors.GreenBlob
                            Phase.Starting, Phase.Stopping -> Color(0xFF3A3326)
                            Phase.Failed -> Color(0xFF3D2427)
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
                            enabled = status.phase != Phase.Stopping,
                            onToggle = onToggle,
                            modifier = Modifier.fillMaxHeight(0.9f).fillMaxWidth(0.78f),
                        )
                    }
                }
            }
        }
        val hint = when {
            !installed -> "Setting up the engine…"
            status.phase == Phase.Running -> "Slide down to stop"
            status.phase == Phase.Starting -> "Starting…"
            status.phase == Phase.Stopping -> "Stopping…"
            else -> "Slide up to start"
        }
        AnimatedContent(hint, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "hint") {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(16.dp))
        EngineCard(status, thermal, engineVersion, installed, onNavigate, onCopy)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) { Icon(icon, contentDescription = label) }
}

@Composable
private fun StatusLine(status: EngineStatus) {
    val (label, color) = when (status.phase) {
        Phase.Running -> "Running" to StatusColors.Green
        Phase.Starting -> "Starting" to StatusColors.Amber
        Phase.Stopping -> "Stopping" to StatusColors.Amber
        Phase.Failed -> "Stopped with an error" to StatusColors.Red
        Phase.Installing -> "Setting up" to StatusColors.Amber
        Phase.NotInstalled -> "Not set up" to MaterialTheme.colorScheme.outline
        Phase.Stopped -> "Engine off" to MaterialTheme.colorScheme.outline
    }
    val c by animateColorAsState(color, label = "dot")
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (status.phase == Phase.Running) c else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EngineCard(
    status: EngineStatus, thermal: Thermal.Reading?, version: String, installed: Boolean,
    onNavigate: (Screen) -> Unit, onCopy: (String) -> Unit,
) {
    val running = status.phase == Phase.Running
    val container by animateColorAsState(if (running) Color(0xFF1C2A1F) else MaterialTheme.colorScheme.surfaceContainer, tween(450), label = "card")
    Card(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Terminal, null, Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Engine", style = MaterialTheme.typography.titleLarge)
                    Text("empryo acp · $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MonitorHeart, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("Uptime", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Uptime(status.runningSince, if (running) StatusColors.Green else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                thermal?.let { t ->
                    val warm = t.status >= 2 || t.headroom >= 0.8f
                    Chip(Icons.Rounded.Thermostat, if (t.headroom.isNaN()) t.label else "${t.label} · ${"%.2f".format(t.headroom)}", if (warm) StatusColors.Amber else null)
                }
                status.bridge?.let { Chip(Icons.Rounded.Wifi, it, null) }
            }
            val info = status.message ?: "Workspace /root/workspace"
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
                    .padding(start = 18.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    info, Modifier.weight(1f).padding(vertical = 14.dp), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    fontFamily = if (status.message == null) FontFamily.Monospace else FontFamily.Default,
                    color = if (status.phase == Phase.Failed) StatusColors.Red else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { onCopy("Empryo $version · ${status.phase} · ${status.bridge ?: "no bridge"}\n$info") }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy")
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onNavigate(Screen.Chat) }, enabled = running, modifier = Modifier.weight(1f).height(60.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Chat, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Open chat", style = MaterialTheme.typography.titleMedium)
                }
                FilledTonalButton(onClick = { onNavigate(Screen.Diagnostics) }, enabled = installed, modifier = Modifier.height(60.dp), shapes = ButtonDefaults.shapes()) {
                    Text("Diagnostics")
                }
            }
        }
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color?) {
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
private fun Uptime(since: Long, color: Color) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (since > 0) { now = System.currentTimeMillis(); delay(1000) }
    }
    val text = if (since <= 0) "—" else {
        val s = ((now - since) / 1000).coerceAtLeast(0)
        when {
            s >= 3600 -> "%dh %02dm".format(s / 3600, s / 60 % 60)
            else -> "%dm %02ds".format(s / 60, s % 60)
        }
    }
    Text(text, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Medium)
}

@Composable
private fun SetupCard(status: EngineStatus, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(22.dp)) {
            if (status.phase == Phase.Installing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(status.installStage ?: "Setting up", style = MaterialTheme.typography.titleMedium)
                        Text("One-time: base system, then Empryo from dl.empryo.com (about 130 MB)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                val f = status.installProgress
                if (f >= 0f) LinearWavyProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text("Engine not set up", style = MaterialTheme.typography.titleMedium)
                status.message?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = StatusColors.Red) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text(if (status.message != null) "Retry" else "Set up") }
            }
        }
    }
}
