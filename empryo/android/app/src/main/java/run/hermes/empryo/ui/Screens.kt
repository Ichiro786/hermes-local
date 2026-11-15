package run.hermes.empryo.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import run.hermes.empryo.LoginUi
import run.hermes.empryo.engine.ChatItem
import run.hermes.empryo.engine.ChatUi

@Composable
private fun TopBar(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = onBack, modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Spacer(Modifier.width(16.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        action()
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(18.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
)

private val Mono = FontFamily.Monospace

// ---------------------------------------------------------------- Model providers

@Composable
fun AccountsScreen(
    login: LoginUi,
    onBack: () -> Unit,
    onLogin: (String) -> Unit,
    onLoginInput: (String) -> Unit,
    onCancelLogin: () -> Unit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSetKey: suspend (String, String) -> String,
    onListModels: suspend () -> String,
    defaultModel: String,
    onSaveModel: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TopBar("Model providers", onBack)
        SectionChip(Icons.Rounded.Add, "SIGN IN")
        Panel {
            Text("Runs empryo --login and opens its link in your browser.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("claude" to "Claude", "codex" to "Codex (ChatGPT)", "codex-device" to "Codex (device code)", "copilot" to "GitHub Copilot").chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, label) ->
                        FilledTonalButton(onClick = { onLogin(id) }, enabled = !login.running, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) { Text(label, maxLines = 1) }
                    }
                }
            }
            if (login.account.isNotEmpty()) {
                Text("empryo --login ${login.account}" + when { login.running -> " · waiting"; login.exitCode == 0 -> " · done"; else -> " · exit ${login.exitCode}" }, style = MaterialTheme.typography.titleSmall)
                login.code?.let { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(code, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontFamily = Mono)
                        OutlinedButton(onClick = { onCopy(code) }) { Text("Copy code") }
                    }
                }
                login.url?.let { url ->
                    Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = Mono)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpen(url) }, shapes = ButtonDefaults.shapes()) { Text("Open link") }
                        OutlinedButton(onClick = { onCopy(url) }) { Text("Copy link") }
                    }
                }
                SelectionContainer {
                    Text(login.lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (login.running) {
                    var paste by remember { mutableStateOf("") }
                    OutlinedTextField(paste, { paste = it }, label = { Text("Paste a code if asked") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onLoginInput(paste); paste = "" }, enabled = paste.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Send") }
                        OutlinedButton(onClick = onCancelLogin) { Text("Cancel") }
                    }
                }
            }
        }
        SectionChip(Icons.Rounded.Add, "API KEY")
        Panel {
            var provider by remember { mutableStateOf("anthropic") }
            var key by remember { mutableStateOf("") }
            var result by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(provider, { provider = it.trim() }, label = { Text("Provider (anthropic, openai, google…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { result = onSetKey(provider, key); key = "" } }, enabled = provider.isNotBlank() && key.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Save key") }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        SectionChip(Icons.Rounded.Add, "MODEL")
        Panel {
            var model by remember(defaultModel) { mutableStateOf(defaultModel) }
            var models by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(model, { model = it }, label = { Text("Default model (provider/model)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSaveModel(model) }, shapes = ButtonDefaults.shapes()) { Text("Save") }
                OutlinedButton(onClick = { scope.launch { models = "Loading…"; models = onListModels() } }) { Text("List models") }
            }
            models?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = Mono) } }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- Diagnostics

@Composable
fun DiagnosticsScreen(report: String, running: Boolean, onBack: () -> Unit, onRun: () -> Unit, onCopy: (String) -> Unit, onShare: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        TopBar("Diagnostics", onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onRun, enabled = !running, shapes = ButtonDefaults.shapes()) { Text(if (report.isEmpty()) "Run diagnostics" else "Run again") }
            FilledTonalIconButton(onClick = { onCopy(report) }, enabled = report.isNotEmpty() && !running) { Icon(Icons.Rounded.ContentCopy, "Copy") }
            FilledTonalIconButton(onClick = onShare, enabled = report.isNotEmpty() && !running) { Icon(Icons.Rounded.Share, "Share") }
        }
        if (running) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
        else Spacer(Modifier.height(12.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            SelectionContainer {
                Text(
                    report.ifEmpty { "Runs empryo --version and doctor, starts the phone bridge once (does port 8787 open?), measures the engine's idle RSS, CPU and context switches, and reads heat and disk. Takes about a minute." },
                    style = MaterialTheme.typography.bodySmall, fontFamily = Mono,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Chat (ACP)

@Composable
fun ChatScreen(chat: ChatUi, engineRunning: Boolean, onBack: () -> Unit, onNew: () -> Unit, onSend: (String) -> Unit, onStop: () -> Unit, onPermission: (String?) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(chat.items.size, chat.items.lastOrNull()) { if (chat.items.isNotEmpty()) list.animateScrollToItem(chat.items.size - 1) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
        TopBar("Chat", onBack) {
            FilledTonalIconButton(onClick = onNew, enabled = engineRunning && !chat.busy) { Icon(Icons.Rounded.Add, "New session") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chat.items.isEmpty()) item {
                Text("Ask Empryo to work in /root/workspace. It asks before every edit and command.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(chat.items) { item -> ChatRow(item) }
        }
        chat.error?.let { Text(it, Modifier.padding(vertical = 6.dp), color = StatusColors.Red, style = MaterialTheme.typography.bodyMedium) }
        chat.permission?.let { p ->
            Panel {
                Text("Allow this?", style = MaterialTheme.typography.titleMedium)
                Text(p.title, fontFamily = Mono, style = MaterialTheme.typography.bodyMedium)
                val allow = p.options.firstOrNull { it.second == "allow_once" }?.first ?: p.options.firstOrNull { it.second.startsWith("allow") }?.first
                val deny = p.options.firstOrNull { it.second == "reject_once" }?.first ?: p.options.firstOrNull { it.second.startsWith("reject") }?.first
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPermission(allow) }, enabled = allow != null, shapes = ButtonDefaults.shapes()) { Text("Allow") }
                    OutlinedButton(onClick = { onPermission(deny) }) { Text("Deny") }
                }
            }
        }
        var text by remember { mutableStateOf("") }
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message Empryo") }, maxLines = 5, shape = RoundedCornerShape(24.dp), enabled = engineRunning)
            Spacer(Modifier.width(8.dp))
            if (chat.busy) FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Stop, "Stop") }
            else FilledIconButton(onClick = { onSend(text.trim()); text = "" }, enabled = engineRunning && text.isNotBlank(), modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send")
            }
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    val shape = RoundedCornerShape(20.dp)
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(item.text, Modifier.clip(shape).background(MaterialTheme.colorScheme.primaryContainer).padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        is ChatItem.Agent -> SelectionContainer {
            Text(item.text, Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp))
        }
        is ChatItem.Tool -> Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp)) {
            Text("⚙ ${item.title}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
            Text(item.status, style = MaterialTheme.typography.labelSmall, color = if (item.status == "failed") StatusColors.Red else MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.output.isNotBlank()) Text(item.output.take(1500), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is ChatItem.Note -> Text(item.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
