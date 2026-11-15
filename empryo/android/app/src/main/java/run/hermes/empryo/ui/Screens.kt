package run.hermes.empryo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import run.hermes.empryo.engine.PairUi
import run.hermes.empryo.engine.Pairing
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
            // One per row: two per row clipped "Codex (ChatGPT)" and "Codex (device code)" to "Codex" twice on the phone.
            listOf(
                "claude" to "Claude", "codex" to "Codex · browser sign-in", "codex-device" to "Codex · code (no browser redirect)", "copilot" to "GitHub Copilot",
            ).forEach { (id, label) ->
                FilledTonalButton(onClick = { onLogin(id) }, enabled = !login.running, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text(label) }
            }
            if (login.account.isNotEmpty()) {
                Text("empryo --login ${login.account}" + when { login.running -> " · waiting"; login.exitCode == 0 -> " · done"; else -> " · exit ${login.exitCode}" }, style = MaterialTheme.typography.titleSmall)
                login.code?.let { code ->
                    Text("Enter this code on the page that opened (it is already copied):", style = MaterialTheme.typography.bodyMedium)
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

// ---------------------------------------------------------------- Pair the official Empryo app

@Composable
fun PairScreen(
    pair: PairUi,
    pairingOn: Boolean,
    engineRunning: Boolean,
    lanAccess: Boolean,
    onBack: () -> Unit,
    onSetPairing: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onVisible: (Boolean) -> Unit,
    onAnswer: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
) {
    DisposableEffect(Unit) { onVisible(true); onDispose { onVisible(false) } }
    LaunchedEffect(pair.state) { if (pair.state == "ready" && pair.code == null) onOpen(pair.via) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TopBar("Pair Empryo app", onBack)
        Text(
            "Use the official Empryo app (iPhone, or the Android beta) with the engine on this phone, the way it pairs with a computer. " +
                "Scan the code with the Empryo app or open the link on the device you pair. A code works for one device for 10 minutes.",
            Modifier.padding(start = 4.dp, bottom = 14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!pairingOn) {
            Panel {
                Text("Pairing is off", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Turning it on runs Empryo's phone service next to the engine" + (if (engineRunning) "." else " and starts the engine.") +
                        " Nothing can connect until you allow it here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { onSetPairing(true) }, shapes = ButtonDefaults.shapes()) { Text("Turn on pairing") }
            }
            return@Column
        }
        when {
            pair.state == "failed" -> Panel {
                Text("Pairing could not start", style = MaterialTheme.typography.titleMedium, color = StatusColors.Red)
                Text(pair.message ?: "phone-service stopped. Share the engine log from Settings.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { onSetPairing(true) }, shapes = ButtonDefaults.shapes()) { Text("Try again") }
            }
            pair.state != "ready" || pair.code == null -> Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(if (engineRunning) "Starting the phone service…" else "Starting the engine…", style = MaterialTheme.typography.titleMedium)
                }
                pair.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.Red) }
            }
            else -> {
                Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(pair.via == "quick", onClick = { onOpen("quick") }, label = { Text("Quick link · anywhere") })
                    FilterChip(pair.via != "quick", onClick = { onOpen("auto") }, enabled = lanAccess, label = { Text("Nearby on this Wi-Fi") })
                }
                if (!lanAccess) Text(
                    "Nearby pairing needs LAN access (Settings). The quick link goes through Empryo's relay and works on any network.",
                    Modifier.padding(start = 4.dp, bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Panel {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(pair.qr, Modifier.size(264.dp)) }
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(pair.openedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                    val left = ((Pairing.expiresAt(pair) - now) / 1000).coerceAtLeast(0)
                    Text(
                        if (left > 0) "Scan with the Empryo app · expires in %d:%02d".format(left / 60, left % 60) else "This code expired. Get a new one.",
                        Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall,
                    )
                    Text(pair.link.orEmpty(), style = MaterialTheme.typography.bodySmall, fontFamily = Mono, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pair.link?.let(onCopy) }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy link") }
                        OutlinedButton(onClick = { onOpen(pair.via) }) { Text("New code") }
                    }
                    pair.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.Red) }
                }
                Panel {
                    Text("How it connects", style = MaterialTheme.typography.titleMedium)
                    pair.reaches.ifEmpty { listOf("none yet") }.forEach { r ->
                        val (kind, addr) = r.substringBefore(' ') to r.substringAfter(' ')
                        Text("${when (kind) { "quick" -> "Quick link (Empryo relay)"; "wifi" -> "Wi-Fi"; "tailscale" -> "Tailscale"; else -> kind }} · ${addr.substringBefore('/')}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "When a device asks to pair you get an Allow prompt here (and a notification). Nearby pairing shows a six-digit code on both screens: allow only if they match.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pair.phones.isNotEmpty()) Panel {
                    Text("Paired devices", style = MaterialTheme.typography.titleMedium)
                    pair.phones.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        if (pair.log.isNotEmpty()) Panel {
            Text("Activity", style = MaterialTheme.typography.titleSmall)
            Text(pair.log.takeLast(8).joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { onSetPairing(false) }, Modifier.padding(bottom = 24.dp)) { Text("Turn off pairing") }
    }
    pair.request?.let { r ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Check the code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (r.computer) "${r.name} wants full control of this phone's engine: every chat, tool and command." else "${r.name} wants to pair over ${if (r.via == "quick") "the quick link" else r.via}.")
                    Text(r.fingerprint, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineMedium, fontFamily = Mono)
                    Text("Allow only if ${r.name} shows this code.", style = MaterialTheme.typography.bodyMedium)
                    if (r.unverified) Text("${r.name} cannot prove it runs the official Empryo app. Allow it only if it is yours.", color = StatusColors.Amber, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = { onAnswer(true) }, shapes = ButtonDefaults.shapes()) { Text("Allow") } },
            dismissButton = { OutlinedButton(onClick = { onAnswer(false) }) { Text("Deny") } },
        )
    }
}

/** Empryo's QR matrix ("0101…" rows) on a white tile with a 4-module quiet zone. */
@Composable
private fun QrCode(cells: List<String>, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(20.dp)).background(Color.White)) {
        val n = cells.size
        if (n == 0) return@Canvas
        val m = size.minDimension / (n + 8)
        cells.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '1') drawRect(Color.Black, Offset((x + 4) * m, (y + 4) * m), Size(m + 0.5f, m + 0.5f))
            }
        }
    }
}
