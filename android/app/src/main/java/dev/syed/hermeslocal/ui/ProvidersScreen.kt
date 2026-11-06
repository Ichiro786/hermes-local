package dev.syed.hermeslocal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.syed.hermeslocal.AppController
import dev.syed.hermeslocal.engine.EngineStatus
import dev.syed.hermeslocal.providers.FlowPhase
import dev.syed.hermeslocal.providers.ProviderFlows
import dev.syed.hermeslocal.providers.ProviderId
import dev.syed.hermeslocal.providers.SignInFlow
import dev.syed.hermeslocal.providers.VoiceKey
import dev.syed.hermeslocal.providers.VoiceKeyKind
import dev.syed.hermeslocal.providers.VoiceStatus
import kotlinx.coroutines.launch

/** Model provider sign-in (runs `hermes-local-auth` in the engine). Works whether or not the server is on. */
@Composable
fun ProvidersScreen(@Suppress("UNUSED_PARAMETER") status: EngineStatus, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val statusInfo by ProviderFlows.status.collectAsStateWithLifecycle()
    val flows by ProviderFlows.flows.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf<ProviderId?>(null) }
    var picker by remember { mutableStateOf<ProviderId?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { ProviderFlows.refresh(); VoiceKey.refresh() }

    // Open the browser once per URL, and only after what the user needs is on screen:
    // Codex: the code (copied too); Antigravity: the link (it has no code to show first).
    for (p in ProviderId.entries) {
        val f = flows[p]
        val ready = f != null && f.running && f.url != null && f.url != f.opened &&
            (p != ProviderId.Codex || f.code != null)
        LaunchedEffect(p, ready, f?.url) {
            if (ready && f != null) {
                ProviderFlows.markOpened(p, f.url!!)
                f.code?.let { AppController.copy("Sign-in code", it) }
                AppController.openUrl(ctx, f.url)
            }
        }
    }

    ScreenFrame("Providers", onBack) {
        Text(
            "Hermes uses these sign-ins for its models. You can also manage providers in the dashboard.",
            Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (p in ProviderId.entries) {
            val info = statusInfo
            val signedIn = info?.signedIn(p) == true
            val f = flows[p]
            val running = f?.running == true
            Card(
                Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (info == null) LoadingIndicator(Modifier.size(28.dp)) else StatusDot(signedIn)
                    }
                    Text(p.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (signedIn && p == ProviderId.Codex && info?.codexAccounts?.isNotEmpty() == true) {
                        Text("Account: ${info.codexAccounts.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (signedIn && info != null) {
                        if (info.isChatProvider(p)) {
                            Text("Used for chat · ${info.model}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (p == ProviderId.Antigravity && info?.agy == false) {
                        Text("agy CLI is missing from this engine. Update the engine.", color = StatusColors.Red, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ProviderFlows.start(p) }, enabled = !running, shapes = ButtonDefaults.shapes()) {
                            Text(if (signedIn) "Sign in again" else "Sign in with ${p.short}")
                        }
                        if (signedIn) {
                            OutlinedButton(onClick = { confirmLogout = p }, enabled = !running, shapes = ButtonDefaults.shapes()) { Text("Sign out") }
                        }
                    }
                    if (signedIn && info != null && !running) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!info.isChatProvider(p)) {
                                FilledTonalButton(onClick = {
                                    scope.launch { notice = ProviderFlows.use(p)?.let { "Could not switch: $it" } }
                                }, shapes = ButtonDefaults.shapes()) { Text("Use for chat") }
                            }
                            TextButton(onClick = { picker = p }) { Text("Choose model") }
                        }
                    }
                    AnimatedVisibility(f != null) {
                        if (f != null) FlowPanel(
                            f,
                            onSendCode = { ProviderFlows.sendCode(p, it) },
                            onCancel = { ProviderFlows.cancel(p) },
                            onDismiss = { ProviderFlows.dismiss(p) },
                        )
                    }
                }
            }
        }
        VoiceKeyCard(VoiceKeyKind.OpenAi)
        VoiceKeyCard(VoiceKeyKind.Gemini)
        notice?.let { Text(it, color = StatusColors.Red, style = MaterialTheme.typography.bodySmall) }
        statusInfo?.error?.let { Text("Status check failed: $it", color = StatusColors.Red, style = MaterialTheme.typography.bodySmall) }
    }

    confirmLogout?.let { p ->
        ConfirmDialog("Sign out of ${p.title}?", "Hermes stops using this provider until you sign in again.", "Sign out", { confirmLogout = null }) {
            scope.launch { notice = ProviderFlows.logout(p)?.let { "Sign-out failed: $it" } }
        }
    }
    picker?.let { p -> ModelPicker(p, current = statusInfo?.takeIf { it.isChatProvider(p) }?.model, onDismiss = { picker = null }) { notice = it } }
}

/** Optional voice API keys, sent to the engine on stdin and kept in Hermes' .env (0600):
 *  OpenAI for GPT-Live (Hermes' voice_live needs a real API key; a ChatGPT/Codex sign-in can't start it),
 *  Gemini for Gemini TTS (Hermes v0.23.0 has no Gemini Live voice mode and no Gemini speech-to-text). */
@Composable
private fun VoiceKeyCard(kind: VoiceKeyKind) {
    val scope = rememberCoroutineScope()
    val voice by VoiceKey.status.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val openAi = kind == VoiceKeyKind.OpenAi
    val title = if (openAi) "OpenAI API key (for GPT-Live)" else "Gemini API key (for Gemini voice)"
    val v = voice
    val saved = if (openAi) v?.openAiKey == true else v?.geminiKey == true
    Card(
        Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (v == null || busy) LoadingIndicator(Modifier.size(28.dp)) else KeyDot(saved)
            }
            Text(
                if (openAi) "Optional. GPT-Live in the Hermes app needs an OpenAI API key (OpenAI bills it per minute); a ChatGPT/Codex " +
                    "sign-in cannot start it. Live Mode with the Hermes speech engine works without a key. The key stays in Hermes' .env on this phone."
                else "Optional. Hermes speaks its replies with Gemini TTS (Google AI Studio key, billed by Google). Hermes has no Gemini Live " +
                    "voice mode and no Gemini speech-to-text, so Live Mode keeps hearing with Whisper. Remove the key to go back to the free Edge voice.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (openAi && v != null && v.error == null && v.sttProvider.isNotEmpty()) Text(voiceLine(v), style = MaterialTheme.typography.bodySmall)
            if (saved && v != null) {
                val ready = if (openAi) v.gptLive else v.ttsProvider == "gemini"
                val what = if (openAi) "GPT-Live" else "Gemini TTS"
                Text(
                    "Saved ${if (openAi) v.keyHint else v.geminiHint} · " + if (ready) "$what ready" else "$what not ready",
                    style = MaterialTheme.typography.bodySmall, color = if (ready) MaterialTheme.colorScheme.primary else StatusColors.Amber,
                )
            }
            val hint = if (openAi) "sk-…" else "AIza…"
            OutlinedTextField(
                key, { key = it.trim(); error = null }, label = { Text(if (saved) "Replace key ($hint)" else "${if (openAi) "OpenAI" else "Gemini"} API key ($hint)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    busy = true; error = null
                    scope.launch {
                        error = VoiceKey.save(kind, key)
                        if (error == null) key = ""
                        busy = false
                    }
                }, enabled = !busy && key.length >= 20, shapes = ButtonDefaults.shapes()) { Text("Save key") }
                if (saved) {
                    OutlinedButton(onClick = { confirmRemove = true }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("Remove") }
                }
            }
            (error ?: v?.error?.takeIf { openAi })?.let { Text(it, color = StatusColors.Red, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            if (openAi) "Remove the OpenAI key?" else "Remove the Gemini key?",
            if (openAi) "GPT-Live stops working. Live Mode with the Hermes speech engine keeps working." else "Hermes goes back to the free Edge voice.",
            "Remove", { confirmRemove = false },
        ) {
            busy = true
            scope.launch { error = VoiceKey.clear(kind); busy = false }
        }
    }
}

private fun voiceLine(v: VoiceStatus): String {
    val hears = if (v.sttProvider == "local") "Whisper ${v.sttModel} on this phone, automatic language" else v.sttProvider
    val speaks = if (v.ttsProvider == "gemini") "Gemini ${v.ttsModel}" else v.ttsProvider.replaceFirstChar { it.uppercase() }
    return "Live Mode hears with $hears · speaks with $speaks"
}

@Composable
private fun KeyDot(on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(6.dp))
        Text(if (on) "Saved" else "Not set", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ModelPicker(p: ProviderId, current: String?, onDismiss: () -> Unit, onResult: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<ProviderFlows.Models?>(null) }
    var chosen by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(p) {
        val m = ProviderFlows.models(p)
        models = m
        if (chosen.isNullOrBlank()) chosen = m.recommended.ifBlank { m.models.firstOrNull() }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("${p.short} model") },
        text = {
            val m = models
            when {
                m == null -> Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(8.dp)); Text("Loading models…") }
                m.models.isEmpty() -> Text(m.error ?: "No models found.")
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(m.models) { id ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = id }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chosen == id, onClick = { chosen = id })
                            Text(id + if (id == m.recommended) "  (recommended)" else "", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && !chosen.isNullOrBlank(), onClick = {
                busy = true
                scope.launch {
                    val err = ProviderFlows.use(p, chosen ?: "")
                    busy = false
                    onResult(err?.let { "Could not set the model: $it" })
                    onDismiss()
                }
            }) { Text(if (busy) "Saving…" else "Use for chat") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
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
private fun FlowPanel(f: SignInFlow, onSendCode: (String) -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var pasted by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val waitingForLink = f.running && (f.url == null || (f.provider == ProviderId.Codex && f.code == null))
        if (waitingForLink || f.phase == FlowPhase.Verifying) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(8.dp))
                Text(if (f.phase == FlowPhase.Verifying) "Checking the sign-in…" else "Starting sign-in…")
            }
        }
        if (f.running) {
            f.code?.let { code ->
                Text("Your code", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(code, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    IconButton(onClick = { AppController.copy("Sign-in code", code) }) { Icon(Icons.Rounded.ContentCopy, "Copy code") }
                }
            }
            val url = f.url
            if (url != null && (f.provider != ProviderId.Codex || f.code != null)) {
                Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { AppController.openUrl(ctx, url) }, shapes = ButtonDefaults.shapes()) { Text("Open link") }
                    TextButton(onClick = { AppController.copy("Sign-in link", url) }) { Text("Copy link") }
                }
            }
            if (f.provider == ProviderId.Antigravity && f.wantsCode) {
                OutlinedTextField(pasted, { pasted = it }, label = { Text("Paste the code from Google") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onSendCode(pasted); pasted = "" }, enabled = pasted.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Submit code") }
            }
            f.error?.let { Text(it, color = StatusColors.Red, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onCancel) { Text("Cancel") }
        } else {
            when (f.phase) {
                FlowPhase.Done -> Text("Signed in ✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                FlowPhase.Cancelled -> Text("Sign-in cancelled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(f.error ?: "Sign-in did not finish.", color = StatusColors.Red)
            }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
        if (f.lines.isNotEmpty()) {
            Text(
                f.lines.takeLast(6).joinToString("\n"), Modifier.heightIn(max = 140.dp),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
