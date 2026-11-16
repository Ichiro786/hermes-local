package dev.syed.hermeslocal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.syed.hermeslocal.AppController
import dev.syed.hermeslocal.engine.EngineState

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lines by EngineState.lines.collectAsStateWithLifecycle()
    val shown = lines.ifEmpty {
        // Not started in this app session: show the tail of the last run from disk.
        remember { runCatching { AppController.paths.engineLog.readLines().takeLast(400) }.getOrDefault(emptyList()) }
    }
    val list = rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.scrollToItem(shown.size - 1) }
    ScreenFrame("Logs", onBack, scroll = false, actions = {
        FilledTonalIconButton(
            onClick = { AppController.shareLog(ctx) }, modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) { Icon(Icons.Rounded.Share, contentDescription = "Share log") }
    }) {
        Card(
            Modifier.fillMaxSize().padding(bottom = 12.dp), shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            SelectionContainer {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(shown) { _, line ->
                        Text(
                            line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                            color = when {
                                line.contains("FAILED") || line.contains("Error") || line.contains("Traceback") -> StatusColors.Red
                                line.startsWith("HERMES_LOCAL_READY") -> StatusColors.Green
                                line.startsWith("---") -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (shown.isEmpty()) item { Text("No output yet. Start Hermes with the lever.", color = MaterialTheme.colorScheme.outline) }
                }
            }
        }
    }
}

@Composable
private fun <T> remember(calc: () -> T): T = androidx.compose.runtime.remember(calc)
