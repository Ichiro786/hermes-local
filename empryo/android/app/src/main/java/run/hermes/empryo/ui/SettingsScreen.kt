package run.hermes.empryo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import run.hermes.empryo.engine.SettingsSnapshot

@Composable
fun SettingsScreen(
    settings: SettingsSnapshot,
    engineOn: Boolean,
    onBack: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onUpdate: (SettingsSnapshot.() -> SettingsSnapshot) -> Unit,
    onShareLog: () -> Unit,
) {
    var editPort by remember { mutableStateOf(false) }
    var editModel by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        // Decorative corner shape from the reference.
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 70.dp, y = (-90).dp).size(240.dp)
                .background(Color(0xFF6E5A9C), MaterialShapes.Cookie6Sided.toShape()),
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.padding(top = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = onBack, modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.width(16.dp))
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
            }
            if (engineOn) Text(
                "Server changes apply the next time the engine starts.", Modifier.padding(start = 8.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionChip(Icons.Rounded.Dns, "SERVER")
            Group {
                NavRow(Icons.Rounded.Lan, "Bridge port", "${settings.bridgePort}") { editPort = true }
                Divider()
                SwitchRow(
                    Icons.Rounded.Hub, "LAN access",
                    if (settings.lanAccess) "Pairing may also listen on Wi-Fi" else "Off: pairing uses the quick link only",
                    settings.lanAccess,
                ) { v -> onUpdate { copy(lanAccess = v) } }
                Divider()
                NavRow(
                    Icons.Rounded.PhoneAndroid, "Pair Empryo app",
                    if (settings.pairOfficial) "On · QR, link and Allow requests" else "Off · use the official Empryo app with this phone",
                ) { onNavigate(Screen.Pair) }
            }

            SectionChip(Icons.Rounded.Palette, "APPEARANCE")
            Group {
                SwitchRow(
                    Icons.Rounded.Contrast, "Theme: AMOLED black",
                    if (settings.amoled) "Pure black background, cards slightly lifted" else "Off: Dark",
                    settings.amoled,
                ) { v -> onUpdate { copy(amoled = v) } }
                Divider()
                val canDynamic = android.os.Build.VERSION.SDK_INT >= 31
                SwitchRow(
                    Icons.Rounded.Wallpaper, "Wallpaper colours (Material You)",
                    if (!canDynamic) "Needs Android 12 or newer" else if (settings.dynamicColor) "Colours follow your wallpaper" else "Off: lavender",
                    settings.dynamicColor && canDynamic, enabled = canDynamic,
                ) { v -> onUpdate { copy(dynamicColor = v) } }
            }

            SectionChip(Icons.Rounded.Shield, "ACCOUNTS")
            Group {
                NavRow(Icons.Rounded.ViewInAr, "Model providers", "Claude, Codex and Copilot sign-in · API keys") { onNavigate(Screen.Accounts) }
                Divider()
                NavRow(Icons.Rounded.Memory, "Default model", settings.defaultModel.ifBlank { "Not set · e.g. anthropic/claude-sonnet-4-5" }) { editModel = true }
            }

            SectionChip(Icons.Rounded.Bolt, "POWER")
            Group {
                SwitchRow(Icons.Rounded.PowerSettingsNew, "Start on boot", "Turn Empryo on when the phone starts", settings.startOnBoot) { v -> onUpdate { copy(startOnBoot = v) } }
                Divider()
                SwitchRow(
                    Icons.Rounded.Bedtime, "Keep awake while running",
                    if (settings.keepAwake) "Holds a partial wake lock the whole time" else "Wake lock only during a reply, plus 45 s",
                    settings.keepAwake,
                ) { v -> onUpdate { copy(keepAwake = v) } }
            }

            SectionChip(Icons.Rounded.MonitorHeart, "ENGINE")
            Group {
                NavRow(Icons.Rounded.MonitorHeart, "Diagnostics", "Version, doctor, bridge test, idle cost, heat, disk") { onNavigate(Screen.Diagnostics) }
                Divider()
                NavRow(Icons.Rounded.Dns, "Share engine log", "engine.log") { onShareLog() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (editPort) TextDialog("Bridge port", "${settings.bridgePort}", KeyboardType.Number, onDismiss = { editPort = false }) { v ->
        v.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> onUpdate { copy(bridgePort = p) } }
        editPort = false
    }
    if (editModel) TextDialog("Default model (provider/model)", settings.defaultModel, KeyboardType.Text, onDismiss = { editModel = false }) { v ->
        onUpdate { copy(defaultModel = v.trim()) }
        editModel = false
    }
}

@Composable
private fun TextDialog(title: String, initial: String, type: KeyboardType, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = type)) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SectionChip(icon: ImageVector, label: String) {
    Row(
        Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
        verticalArrangement = Arrangement.Top,
    ) { content() }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 60.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
