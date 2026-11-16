package dev.syed.hermeslocal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.syed.hermeslocal.AppController
import dev.syed.hermeslocal.engine.EngineStatus
import dev.syed.hermeslocal.engine.Phase
import dev.syed.hermeslocal.engine.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One distinct colour per part, all from the theme (engine, deps, sessions, agy, caches, logs, leftovers). */
@Composable
private fun partColors(): List<Color> {
    val c = MaterialTheme.colorScheme
    return listOf(c.primary, c.tertiary, c.secondary, MaterialTheme.status.busy, c.inversePrimary, c.outline, c.error)
}

/** What the app's data is made of, plus "Clean up" (engine stopped) and "Reset". */
@Composable
fun StorageScreen(status: EngineStatus, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<Storage.Report?>(null) }
    var scans by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(scans, status.phase == Phase.Installing) {
        report = null
        report = withContext(Dispatchers.IO) { AppController.storage.scan() }
    }
    val on = status.isOn || status.phase == Phase.Stopping
    val installing = status.phase == Phase.Installing

    ScreenFrame("Storage", onBack) {
        SectionChip(Icons.Rounded.PieChart, "Breakdown")
        Group {
            AnimatedContent(report, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentKey = { it == null }, label = "scan") { r ->
                if (r == null) Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(36.dp)); Spacer(Modifier.width(12.dp))
                    Text("Measuring…", style = MaterialTheme.typography.bodyLarge)
                } else Column(Modifier.padding(20.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(bytesLabel(r.total), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
                    Text("App data on this phone", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val colors = partColors()
                    UsageBar(r, colors)
                    r.parts.forEachIndexed { i, p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(10.dp)) { drawCircle(colors[i % colors.size]) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.label, style = MaterialTheme.typography.titleSmall)
                                if (p.detail.isNotEmpty()) Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(bytesLabel(p.bytes), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }

        SectionChip(Icons.Rounded.CleaningServices, "Clean up")
        Group {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val r = report
                Text(
                    when {
                        r == null -> "Measuring what can go…"
                        r.cleanable <= 0 -> "Nothing to clean: no unused dependency generations, caches or leftovers."
                        else -> "${bytesLabel(r.cleanable)} can go: unused dependency generations (each plugin install leaves one), " +
                            "Hermes' download caches, agy update copies and interrupted installs. Your data stays."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (on) Text("Stop Hermes with the lever first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.status.busy)
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.status.on) }
                Button(
                    onClick = {
                        working = true; notice = null
                        scope.launch {
                            try {
                                val freed = AppController.cleanUp()
                                notice = if (freed == null) "Stop Hermes first." else "Freed ${bytesLabel(freed)}."
                            } catch (e: Exception) {
                                notice = "Clean up failed: ${e.message}"
                            } finally {
                                working = false; scans++
                            }
                        }
                    },
                    enabled = !working && !on && !installing && (r?.cleanable ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shapes = ButtonDefaults.shapes(),
                ) { if (working) LoadingIndicator(Modifier.size(24.dp)) else Text("Clean up") }
            }
        }

        SectionChip(Icons.Rounded.SdStorage, "Reset")
        Group {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Deletes the engine and all Hermes data on this phone: config, provider logins, sessions, memories and the dashboard " +
                        "password. The engine downloads again afterwards.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(
                    onClick = { confirmReset = true }, enabled = !working && !installing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shapes = ButtonDefaults.shapes(),
                ) { Text("Reset everything", color = MaterialTheme.colorScheme.error) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (confirmReset) ConfirmDialog(
        "Reset everything?", "This deletes the engine and all Hermes data on this phone. It cannot be undone.", "Reset",
        { confirmReset = false },
    ) { working = true; AppController.reset { working = false; scans++ } }
}

/** One stacked bar of the parts; widths animate in after each scan. */
@Composable
private fun UsageBar(r: Storage.Report, colors: List<Color>) {
    val sum = r.parts.sumOf { it.bytes }.coerceAtLeast(1L).toFloat()
    val grow = remember(r) { Animatable(0f) }
    LaunchedEffect(r) { grow.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 200f)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        var x = 0f
        r.parts.forEachIndexed { i, p ->
            val w = size.width * (p.bytes / sum) * grow.value // read in draw only: the bar grows without recomposing
            if (w > 0.5f) drawRoundRect(colors[i % colors.size], Offset(x, 0f), Size(w, size.height), CornerRadius(size.height / 2))
            x += w
        }
    }
}
