package dev.syed.hermeslocal.providers

import android.util.Log
import dev.syed.hermeslocal.AppController
import dev.syed.hermeslocal.BuildConfig
import dev.syed.hermeslocal.engine.CommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class ProviderId(val id: String, val hermesId: String, val title: String, val short: String, val blurb: String) {
    Codex(
        "codex", "openai-codex", "Codex (ChatGPT)", "Codex",
        "Sign in with your ChatGPT plan. OpenAI device code: open the link, enter the code.",
    ),
    Antigravity(
        "antigravity", "antigravity-oauth", "Antigravity (Gemini, Claude)", "Antigravity",
        "Google sign-in through the official agy CLI. Paste the code from Google back here.",
    ),
}

enum class FlowPhase { Starting, Waiting, Verifying, Done, Failed, Cancelled }

data class SignInFlow(
    val provider: ProviderId,
    val phase: FlowPhase = FlowPhase.Starting,
    val url: String? = null,
    val code: String? = null,
    /** agy is waiting for the code from Google. */
    val wantsCode: Boolean = false,
    val error: String? = null,
    val detail: String? = null,
    val lines: List<String> = emptyList(),
    /** The URL already opened in the browser (opened once, after the code is on screen). */
    val opened: String? = null,
    /** Which start() this flow belongs to: a cancelled flow's process exiting later must not touch a newer flow. */
    val gen: Long = 0,
) {
    val running get() = phase == FlowPhase.Starting || phase == FlowPhase.Waiting || phase == FlowPhase.Verifying
}

data class ProviderStatus(
    val codex: Boolean = false,
    val codexAccounts: List<String> = emptyList(),
    val antigravity: Boolean = false,
    val agy: Boolean = true,
    val modelProvider: String = "",
    val model: String = "",
    val error: String? = null,
) {
    fun signedIn(p: ProviderId) = if (p == ProviderId.Codex) codex else antigravity
    fun isChatProvider(p: ProviderId) = modelProvider == p.hermesId

    companion object {
        fun parse(output: String): ProviderStatus {
            val json = output.lines().lastOrNull { it.trim().startsWith("{") }
                ?: return ProviderStatus(error = output.trim().takeLast(300).ifEmpty { "no answer from the engine" })
            return try {
                val o = JSONObject(json)
                if (o.has("error") && !o.has("codex")) return ProviderStatus(error = o.optString("error"))
                val acc = o.optJSONArray("codex_accounts")
                ProviderStatus(
                    codex = o.optInt("codex") == 1,
                    codexAccounts = (0 until (acc?.length() ?: 0)).map { acc!!.optString(it) },
                    antigravity = o.optInt("antigravity") == 1,
                    agy = o.optInt("agy", 1) == 1,
                    modelProvider = o.optString("model_provider"),
                    model = o.optString("model_model"),
                )
            } catch (e: Exception) {
                ProviderStatus(error = "bad status: ${e.message}")
            }
        }
    }
}

/**
 * Provider sign-in flows, process-wide (they survive leaving the screen and the trip to the browser).
 * Each provider has its own flow and its own guest process: one never blocks the other.
 * Every way out of a running flow (done, failed, cancel, timeout, process exit) ends in a non-running
 * phase, so the buttons are always usable again.
 */
object ProviderFlows {
    private val AUTH = listOf("/bin/bash", "/opt/hermes-local/bin/hermes-local-auth")
    private const val TAG = "HermesLocalCI"

    /** No URL/code within this long after start: the flow failed (proot cold start included). */
    const val START_TIMEOUT_MS = 120_000L
    /** Hermes polls OpenAI for 15 min; agy waits for the code. */
    private fun totalTimeoutMs(p: ProviderId) = if (p == ProviderId.Codex) 16 * 60_000L else 11 * 60_000L

    private val _flows = MutableStateFlow<Map<ProviderId, SignInFlow>>(emptyMap())
    val flows: StateFlow<Map<ProviderId, SignInFlow>> = _flows.asStateFlow()
    private val _status = MutableStateFlow<ProviderStatus?>(null)
    val status: StateFlow<ProviderStatus?> = _status.asStateFlow()

    private val sessions = HashMap<ProviderId, CommandRunner.Session>()
    private val jobs = HashMap<ProviderId, Job>()
    private var refreshJob: Job? = null

    private var nextGen = 0L

    private fun set(p: ProviderId, gen: Long? = null, f: (SignInFlow) -> SignInFlow) =
        _flows.update { m -> m[p]?.takeIf { gen == null || it.gen == gen }?.let { m + (p to f(it)) } ?: m }

    private fun ci(msg: String) {
        if (BuildConfig.CI_HOOKS) Log.i(TAG, msg)
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = AppController.scope.launch {
            val r = AppController.runner.run(AUTH + "status", timeoutSec = 150)
            _status.value = ProviderStatus.parse(r.output)
            ci("status: ${r.output.trim().takeLast(400)}")
        }
    }

    fun start(p: ProviderId) {
        if (_flows.value[p]?.running == true) return
        val gen = ++nextGen
        _flows.update { it + (p to SignInFlow(p, gen = gen)) }
        ci("flow $p: start")
        jobs[p]?.cancel()
        jobs[p] = AppController.scope.launch(Dispatchers.IO) {
            val parser = SignInParser()
            val session = try {
                AppController.runner.startSession(AUTH + listOf("login", p.id)) { raw -> onLine(p, gen, parser, raw) }
            } catch (e: Exception) {
                finish(p, gen, FlowPhase.Failed, e.message ?: "could not start the engine")
                return@launch
            }
            synchronized(sessions) { sessions[p] = session }
            val watchdog = launch {
                delay(START_TIMEOUT_MS)
                val f = _flows.value[p]
                if (f != null && f.gen == gen && f.running && f.url == null) {
                    finish(p, gen, FlowPhase.Failed, "No sign-in link after ${START_TIMEOUT_MS / 1000} s. Check the connection and try again.")
                    session.cancel()
                    return@launch
                }
                delay(totalTimeoutMs(p) - START_TIMEOUT_MS)
                if (_flows.value[p]?.running == true) {
                    finish(p, gen, FlowPhase.Failed, "Sign-in timed out. Try again.")
                    session.cancel()
                }
            }
            val exit = session.waitFor()
            watchdog.cancel()
            synchronized(sessions) { if (sessions[p] === session) sessions.remove(p) }
            val f = _flows.value[p]
            if (f != null && f.gen == gen && f.running) {
                if (exit == 0) finish(p, gen, FlowPhase.Done, f.detail)
                else finish(p, gen, FlowPhase.Failed, f.error ?: f.lines.lastOrNull() ?: "Sign-in did not finish (exit $exit).")
            }
            ci("flow $p: exit $exit phase=${_flows.value[p]?.phase}")
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    private fun onLine(p: ProviderId, gen: Long, parser: SignInParser, raw: String) {
        val ev = parser.feed(raw) ?: return
        // The URL and code also go into the log under "1. Open this URL / 2. Enter this code:".
        fun log(text: String) = set(p, gen) { it.copy(lines = (it.lines + text).takeLast(40)) }
        when (ev) {
            is SignInParser.Event.Url -> { set(p, gen) { it.copy(url = ev.url, phase = FlowPhase.Waiting, error = null) }; log("   ${ev.url}"); ci("flow $p: url ${ev.url}") }
            is SignInParser.Event.Code -> { set(p, gen) { it.copy(code = ev.code, phase = FlowPhase.Waiting) }; log("   ${ev.code}"); ci("flow $p: code ${ev.code}") }
            SignInParser.Event.Prompt -> { set(p, gen) { it.copy(wantsCode = true, phase = FlowPhase.Waiting) }; ci("flow $p: prompt code") }
            is SignInParser.Event.Error -> { set(p, gen) { it.copy(error = ev.message, phase = FlowPhase.Waiting, url = null, wantsCode = false) }; ci("flow $p: error ${ev.message}") }
            is SignInParser.Event.Done -> {
                if (ev.ok) set(p, gen) { it.copy(phase = FlowPhase.Verifying, detail = ev.detail) }
                else set(p, gen) { it.copy(error = ev.detail.ifBlank { "Sign-in failed." }) }
                ci("flow $p: done ok=${ev.ok} ${ev.detail}")
            }
            is SignInParser.Event.Model -> ci("flow $p: model ${ev.json}")
            is SignInParser.Event.State -> log(ev.text)
            is SignInParser.Event.Line -> log(ev.text)
        }
    }

    private fun finish(p: ProviderId, gen: Long?, phase: FlowPhase, message: String?) {
        set(p, gen) {
            if (!it.running) it
            else it.copy(phase = phase, wantsCode = false,
                error = if (phase == FlowPhase.Failed) message else null,
                detail = if (phase == FlowPhase.Done) message else it.detail)
        }
    }

    /** Cancel ends the flow and removes its panel at once (test2 left a "Sign-in cancelled." card until Close). */
    fun cancel(p: ProviderId) {
        _flows.update { it - p }
        val s = synchronized(sessions) { sessions.remove(p) }
        ci("flow $p: cancel")
        AppController.scope.launch(Dispatchers.IO) { s?.cancel() }
    }

    fun sendCode(p: ProviderId, code: String) {
        val s = synchronized(sessions) { sessions[p] } ?: return
        set(p) { it.copy(wantsCode = false, phase = FlowPhase.Verifying, error = null) }
        AppController.scope.launch(Dispatchers.IO) {
            if (!s.send(code.trim() + "\n")) finish(p, null, FlowPhase.Failed, "The sign-in process ended before the code arrived.")
        }
    }

    fun markOpened(p: ProviderId, url: String) = set(p) { it.copy(opened = url) }

    fun dismiss(p: ProviderId) = _flows.update { m -> if (m[p]?.running == true) m else m - p }

    suspend fun logout(p: ProviderId): String? {
        val r = AppController.runner.run(AUTH + listOf("logout", p.id), timeoutSec = 120)
        refresh()
        return if (r.exitCode == 0) null else r.output.lines().lastOrNull { it.isNotBlank() }
    }

    data class Models(val models: List<String>, val recommended: String, val error: String? = null)

    suspend fun models(p: ProviderId): Models {
        val r = AppController.runner.run(AUTH + listOf("models", p.id), timeoutSec = 150)
        val json = r.output.lines().lastOrNull { it.trim().startsWith("{") } ?: return Models(emptyList(), "", r.output.takeLast(200))
        return try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("models")
            Models((0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }, o.optString("recommended"))
        } catch (e: Exception) {
            Models(emptyList(), "", e.message)
        }
    }

    /** Makes [p] (and [model], or Hermes' recommended one) the chat model. Returns an error or null. */
    suspend fun use(p: ProviderId, model: String = ""): String? {
        val r = AppController.runner.run(AUTH + listOf("use", p.id) + listOfNotNull(model.ifBlank { null }), timeoutSec = 150)
        refresh()
        val json = r.output.lines().lastOrNull { it.trim().startsWith("{") } ?: return r.output.takeLast(200).ifBlank { "no answer" }
        return try {
            val o = JSONObject(json)
            if (o.optBoolean("ok")) null else o.optString("error", "could not set the model")
        } catch (e: Exception) {
            e.message
        }
    }
}
