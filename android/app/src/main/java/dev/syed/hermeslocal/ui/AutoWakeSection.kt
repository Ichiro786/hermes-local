package dev.syed.hermeslocal.ui

import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.syed.hermeslocal.engine.SettingsSnapshot
import dev.syed.hermeslocal.wake.AutoWake

/** Settings > Auto-wake: the opt-in toggle, what Android currently allows, and the last wake. */
@Composable
fun AutoWakeSection(settings: SettingsSnapshot) {
    val ctx = LocalContext.current
    // Access is granted or revoked in Android's settings, outside the app: re-read it whenever we come back.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumed++; onPauseOrDispose {} }
    val connected by AutoWake.connected.collectAsStateWithLifecycle()
    val last by AutoWake.last.collectAsStateWithLifecycle()
    val setup = remember(resumed, connected, settings.autoWake) { AutoWake.setup(ctx) }
    val hermesInstalled = remember(resumed) { AutoWake.officialHermesInstalled(ctx) }
    var dialog by remember { mutableStateOf(false) }

    SectionChip(Icons.Rounded.AutoMode, "Auto-wake")
    Group {
        SwitchRow(
            Icons.Rounded.RocketLaunch, "Auto-wake with official Hermes",
            if (settings.autoWake) "Opening the Hermes app starts this server when it is off"
            else "Off: start Hermes here with the lever",
            settings.autoWake,
        ) { on ->
            AutoWake.setEnabled(ctx, on)
            if (on && !AutoWake.accessGranted(ctx)) dialog = true
        }
        if (settings.autoWake) {
            GroupDivider()
            val (icon, title, subtitle) = when (setup) {
                AutoWake.Setup.Active -> Triple(Icons.Rounded.CheckCircle, "Active", "Watching for the Hermes app only · never stops the server")
                AutoWake.Setup.NeedsAccess -> Triple(Icons.Rounded.ErrorOutline, "Needs accessibility access", "Tap to allow it in Android's settings")
                AutoWake.Setup.NotRunning -> Triple(
                    Icons.Rounded.ErrorOutline, "Allowed, but not running",
                    if (AutoWake.isColorOs) "ColorOS stopped it. Tap for the fix" else "Android stopped it. Turn it off and on in accessibility settings",
                )
                AutoWake.Setup.Off -> Triple(Icons.Rounded.Info, "Off", null)
            }
            NavRow(icon, title, subtitle, chevron = setup != AutoWake.Setup.Active) {
                if (setup != AutoWake.Setup.Active) dialog = true
            }
            if (!hermesInstalled) {
                GroupDivider()
                NavRow(Icons.Rounded.Info, "Official Hermes app not found", "Auto-wake reacts to ${dev.syed.hermeslocal.wake.WakePolicy.OFFICIAL_HERMES} only", chevron = false) {}
            }
            last?.let { w ->
                GroupDivider()
                val ago = DateUtils.getRelativeTimeSpanString(w.atMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                NavRow(Icons.Rounded.History, "Last wake · $ago", w.result, chevron = false) {}
            }
        }
    }

    if (dialog) AutoWakeSetupDialog(onDismiss = { dialog = false })
}

@Composable
private fun AutoWakeSetupDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { androidx.compose.material3.Icon(Icons.Rounded.AutoMode, null) },
        title = { Text("Allow auto-wake") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. In Accessibility, open \"Hermes Local auto-wake\" (under Downloaded or Installed apps) and turn it on.")
                if (Build.VERSION.SDK_INT >= 33) {
                    Text("2. If Android says \"Restricted setting\": open App info, tap ⋮ (top right), choose \"Allow restricted settings\", then try again.")
                }
                if (AutoWake.isColorOs) {
                    Text("ColorOS: in App info > Battery, allow background activity (or set \"Don't optimise\"), or ColorOS turns the service off after a while.")
                }
                Text(
                    "Hermes Local only learns that the official Hermes app came to the front. It cannot read your screen or other apps, and it never stops the server.",
                    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { AutoWake.openAccessibilitySettings(ctx); onDismiss() }) { Text("Open accessibility") } },
        dismissButton = { TextButton(onClick = { AutoWake.openAppInfo(ctx); onDismiss() }) { Text("App info") } },
    )
}
