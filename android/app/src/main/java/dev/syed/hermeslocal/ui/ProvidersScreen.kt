package dev.syed.hermeslocal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.syed.hermeslocal.AppController
import dev.syed.hermeslocal.engine.CommandRunner
import dev.syed.hermeslocal.engine.EngineStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Provider(val id: String, val title: String, val short: String, val blurb: String) {
    Codex("codex", "Codex (ChatGPT)", "Codex", "Sign in with your ChatGPT plan. OpenAI device code: open the link, enter the code."),
    Antigravity("antigravity", "Antigravity (Gemini, Claude)", "Antigravity", "Google sign-in through the official agy CLI. Paste the code back here within a minute."),
}

private data class SignInFlow(
    val provider: Provider,
    val lines: List<String> = emptyList(),
    val url: String? = null,
    val code: String? = null,
    val exitCode: Int? = null,
)

private val URL_RE = Regex("""https?://[^\s'"<>]+""")
private val CODE_RE = Regex("""\b[A-Z0-9]{4,}-[A-Z0-9]{4,}\b""")
private val AUTH = listOf("/bin/bash", "/opt/hermes-local/bin/hermes-local-auth")

/** Model provider sign-in (runs `hermes-local-auth` in the engine). Works whether or not the server is on. */
@Composable
fun ProvidersScreen(@Suppress("UNUSED_PARAMETER") status: EngineStatus, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusMap by remember { mutableStateOf<Map<String, String>?>(null) }
    val flowState = remember { MutableStateFlow<SignInFlow?>(null) }
    val current by flowState.collectAsState()
    var session by remember { mutableStateOf<CommandRunner.Session?>(null) }
    var confirmLogout by remember { mutableStateOf<Provider?>(null) }
    var opened by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        statusMap = null
        val r = AppController.runner.run(AUTH + "status", timeoutSec = 150)
        statusMap = r.output.lines()
            .mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            .toMap().ifEmpty { mapOf("error" to r.output.take(300)) }
    }
    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(Unit) { onDispose { session?.cancel() } }

    fun start(p: Provider) {
        session?.cancel()
        opened = null
        flowState.value = SignInFlow(p)
        scope.launch(Dispatchers.IO) {
            val s = try {
                AppController.runner.startSession(AUTH + listOf("login", p.id)) { line ->
                    flowState.update { f ->
                        f?.copy(
                            lines = (f.lines + line).takeLast(60),
                            url = f.url ?: URL_RE.find(line)?.value?.trimEnd('.', ',', ')'),
                            code = f.code ?: if (line.contains("code", ignoreCase = true)) CODE_RE.find(line)?.value else null,
                        )
                    }
                }
            } catch (e: Exception) {
                flowState.update { it?.copy(lines = it.lines + (e.message ?: "failed"), exitCode = -1) }
                return@launch
            }
            withContext(Dispatchers.Main) { session = s }
            val code = s.waitFor()
            flowState.update { it?.copy(exitCode = code) }
            withContext(Dispatchers.Main) { session = null; refresh() }
        }
    }

    // Show the link at once and open it, so a short code timeout (agy: ~60 s) is not wasted.
    LaunchedEffect(current?.url) {
        val f = current ?: return@LaunchedEffect
        val url = f.url ?: return@LaunchedEffect
        if (opened != url) {
            opened = url
            f.code?.let { AppController.copy("Sign-in code", it) }
            AppController.openUrl(ctx, url)
        }
    }

    ScreenFrame("Providers", onBack) {
        Text(
            "Hermes uses these sign-ins for its models. You can also manage providers in the dashboard.",
            Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (p in Provider.entries) {
            val signedIn = statusMap?.get(p.id) == "1"
            val active = current?.provider == p && current?.exitCode == null
            Card(
                Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (statusMap == null) LoadingIndicator(Modifier.size(28.dp)) else StatusDot(signedIn)
                    }
                    Text(p.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p == Provider.Antigravity && statusMap?.get("agy") == "0") {
                        Text("agy CLI is missing from this engine. Update the engine.", color = StatusColors.Red, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { start(p) }, enabled = !active && session == null, shapes = ButtonDefaults.shapes()) {
                            Text(if (signedIn) "Sign in again" else "Sign in with ${p.short}")
                        }
                        if (signedIn) {
                            OutlinedButton(onClick = { confirmLogout = p }, enabled = session == null, shapes = ButtonDefaults.shapes()) { Text("Sign out") }
                        }
                    }
                    val f = current
                    AnimatedVisibility(f != null && f.provider == p) {
                        if (f != null) FlowPanel(f, onSendCode = { code -> session?.send(code.trim() + "\n") }, onCancel = { session?.cancel() })
                    }
                }
            }
        }
        statusMap?.get("error")?.let { Text("Status check failed: $it", color = StatusColors.Red, style = MaterialTheme.typography.bodySmall) }
    }

    confirmLogout?.let { p ->
        ConfirmDialog("Sign out of ${p.title}?", "Hermes stops using this provider until you sign in again.", "Sign out", { confirmLogout = null }) {
            scope.launch {
                AppController.runner.run(AUTH + listOf("logout", p.id), timeoutSec = 90)
                refresh()
            }
        }
    }
}

@Composable
private fun StatusDot(on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(6.dp))
        Text(if (on) "Signed in" else "Not signed in", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FlowPanel(f: SignInFlow, onSendCode: (String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var pasted by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (f.exitCode == null && f.url == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(8.dp)); Text("Starting sign-in…")
            }
        }
        f.code?.let { code ->
            Text("Your code", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(code, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                IconButton(onClick = { AppController.copy("Sign-in code", code) }) { Icon(Icons.Rounded.ContentCopy, "Copy code") }
            }
        }
        f.url?.let { url ->
            Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { AppController.openUrl(ctx, url) }, shapes = ButtonDefaults.shapes()) { Text("Open link") }
                TextButton(onClick = { AppController.copy("Sign-in link", url) }) { Text("Copy link") }
            }
        }
        if (f.provider == Provider.Antigravity && f.url != null && f.exitCode == null) {
            OutlinedTextField(pasted, { pasted = it }, label = { Text("Paste the code from Google") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSendCode(pasted); pasted = "" }, enabled = pasted.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Submit code") }
        }
        when (f.exitCode) {
            null -> TextButton(onClick = onCancel) { Text("Cancel") }
            0 -> Text("Signed in ✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            else -> Text("Sign-in did not finish (exit ${f.exitCode}).", color = StatusColors.Red)
        }
        Text(
            f.lines.takeLast(8).joinToString("\n"), Modifier.heightIn(max = 160.dp),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
