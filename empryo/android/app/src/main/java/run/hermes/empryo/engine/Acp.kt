package run.hermes.empryo.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AcpError(message: String) : Exception(message)

/**
 * JSON-RPC 2.0 over the stdio of `empryo acp` (one JSON object per line), the Agent Client Protocol.
 * Responses complete [request]; `session/update` notifications and agent->client requests
 * (`session/request_permission`) go to [Chat].
 */
class AcpClient(private val stdin: OutputStream) {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val writeLock = Any()

    private fun write(obj: JSONObject) = synchronized(writeLock) {
        stdin.write((obj.toString() + "\n").toByteArray())
        stdin.flush()
    }

    suspend fun request(method: String, params: JSONObject, timeoutMs: Long = 60_000): JSONObject {
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        try {
            write(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
            return withTimeout(timeoutMs) { d.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun notify(method: String, params: JSONObject) =
        runCatching { write(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)) }

    fun respond(id: Any, result: JSONObject) =
        runCatching { write(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)) }

    /** One stdout line from the engine. Returns false when it was not JSON-RPC (logged by the caller). */
    fun onLine(line: String): Boolean {
        val m = runCatching { JSONObject(line) }.getOrNull() ?: return false
        val method = m.optString("method", "")
        when {
            method.isNotEmpty() && m.has("id") -> Chat.onAgentRequest(this, m.get("id"), method, m.optJSONObject("params") ?: JSONObject())
            method.isNotEmpty() -> if (method == "session/update") m.optJSONObject("params")?.let { Chat.onUpdate(it) }
            else -> {
                val d = pending[m.optInt("id", -1)] ?: return true
                val err = m.optJSONObject("error")
                if (err != null) d.completeExceptionally(AcpError(errorText(err)))
                else d.complete(m.optJSONObject("result") ?: JSONObject())
            }
        }
        return true
    }

    /** JSON-RPC error message plus its `data` (Empryo puts the real cause there; "Internal error" alone says nothing). */
    internal fun errorText(err: JSONObject): String {
        val msg = err.optString("message").ifBlank { "error ${err.optInt("code")}" }
        val detail = when (val d = err.opt("data")) {
            null, JSONObject.NULL -> ""
            is JSONObject -> listOf("details", "message", "error", "reason").firstNotNullOfOrNull { k -> d.optString(k).takeIf { it.isNotBlank() } } ?: d.toString()
            else -> d.toString()
        }.trim()
        return if (detail.isEmpty() || detail == msg) msg else "$msg: ${detail.take(800)}"
    }

    fun failAll(why: String) {
        pending.values.forEach { it.completeExceptionally(AcpError(why)) }
        pending.clear()
    }

    suspend fun initialize(): JSONObject = request(
        "initialize", timeoutMs = 120_000, params =
        JSONObject().put("protocolVersion", 1).put(
            "clientCapabilities",
            JSONObject().put("fs", JSONObject().put("readTextFile", false).put("writeTextFile", false)).put("terminal", false),
        ),
    )
}

sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Agent(val id: String, val text: String) : ChatItem
    data class Tool(val id: String, val title: String, val status: String, val output: String = "") : ChatItem
    data class Note(val text: String) : ChatItem
}

data class Permission(val id: Any, val title: String, val options: List<Pair<String, String>>) // optionId to kind

data class ChatUi(
    val sessionId: String? = null,
    val items: List<ChatItem> = emptyList(),
    val busy: Boolean = false,
    val permission: Permission? = null,
    val error: String? = null,
)

/** The one chat session the app shows, fed by the service's AcpClient. */
object Chat {
    const val SLASH_HINT = "Slash commands like /remote only work in Empryo's terminal app, not in this chat. " +
        "To pair the official Empryo app use Settings > Pair Empryo app; for models use Model providers."
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()
    @Volatile var client: AcpClient? = null
    /** Turn start/end, for the service's wake lock. */
    @Volatile var onTurn: (Boolean) -> Unit = {}

    fun reset() { _ui.value = ChatUi() }

    suspend fun newSession(cwd: String): String? {
        val c = client ?: run { _ui.update { it.copy(error = "Start the engine first") }; return null }
        _ui.value = ChatUi()
        return try {
            val r = c.request("session/new", JSONObject().put("cwd", cwd).put("mcpServers", JSONArray()))
            val id = r.getString("sessionId")
            // Ask before every edit and command, so the app's Allow / Deny is what decides.
            runCatching { c.request("session/set_config_option", JSONObject().put("sessionId", id).put("configId", "approvals").put("value", "ask")) }
            val model = r.optJSONArray("configOptions")?.let { opts ->
                (0 until opts.length()).map { opts.getJSONObject(it) }.firstOrNull { it.optString("id") == "model" }?.optString("currentValue")
            }
            _ui.update { it.copy(sessionId = id, items = listOf(ChatItem.Note("New session · ${model ?: "default model"} · $cwd"))) }
            id
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.message ?: e.toString()) }
            null
        }
    }

    suspend fun send(text: String) {
        if (text.startsWith("/")) {
            // ACP has no slash commands; Empryo's /remote, /model etc. only exist in its terminal UI.
            _ui.update { it.copy(items = it.items + ChatItem.User(text) + ChatItem.Note(SLASH_HINT), error = null) }
            return
        }
        val c = client ?: run { _ui.update { it.copy(error = "Start the engine first") }; return }
        val sid = _ui.value.sessionId ?: newSession(Paths.WORKSPACE) ?: return
        _ui.update { it.copy(items = it.items + ChatItem.User(text), busy = true, error = null) }
        onTurn(true)
        try {
            val r = c.request(
                "session/prompt",
                JSONObject().put("sessionId", sid).put("prompt", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
                timeoutMs = 60L * 60_000,
            )
            val stop = r.optString("stopReason")
            if (stop.isNotEmpty() && stop != "end_turn") _ui.update { it.copy(items = it.items + ChatItem.Note("Stopped: $stop")) }
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            _ui.update { it.copy(busy = false, permission = null) }
            onTurn(false)
        }
    }

    fun cancel() {
        val sid = _ui.value.sessionId ?: return
        client?.notify("session/cancel", JSONObject().put("sessionId", sid))
    }

    fun answerPermission(optionId: String?) {
        val p = _ui.value.permission ?: return
        val outcome = if (optionId == null) JSONObject().put("outcome", "cancelled")
        else JSONObject().put("outcome", "selected").put("optionId", optionId)
        client?.respond(p.id, JSONObject().put("outcome", outcome))
        _ui.update { it.copy(permission = null) }
    }

    internal fun onAgentRequest(c: AcpClient, id: Any, method: String, params: JSONObject) {
        if (method != "session/request_permission") {
            // Nothing else is advertised in clientCapabilities; refuse politely so the agent never waits.
            c.respond(id, JSONObject())
            return
        }
        val tc = params.optJSONObject("toolCall") ?: JSONObject()
        val opts = params.optJSONArray("options") ?: JSONArray()
        val options = (0 until opts.length()).map { opts.getJSONObject(it) }.map { it.optString("optionId") to it.optString("kind") }
        _ui.update { it.copy(permission = Permission(id, tc.optString("title", "The agent wants to act"), options)) }
    }

    internal fun onUpdate(params: JSONObject) {
        val u = params.optJSONObject("update") ?: return
        _ui.update { s ->
            when (u.optString("sessionUpdate")) {
                "agent_message_chunk" -> {
                    val text = u.optJSONObject("content")?.optString("text").orEmpty()
                    val mid = u.optString("messageId", "agent")
                    val last = s.items.lastOrNull()
                    if (last is ChatItem.Agent && last.id == mid) s.copy(items = s.items.dropLast(1) + last.copy(text = last.text + text))
                    else s.copy(items = s.items + ChatItem.Agent(mid, text))
                }
                "tool_call" -> s.copy(items = s.items + ChatItem.Tool(u.optString("toolCallId"), u.optString("title", "tool"), u.optString("status", "pending")))
                "tool_call_update" -> {
                    val id = u.optString("toolCallId")
                    val out = u.optJSONArray("content")?.let { arr ->
                        (0 until arr.length()).joinToString("") { arr.getJSONObject(it).optJSONObject("content")?.optString("text").orEmpty() }
                    }.orEmpty()
                    s.copy(items = s.items.map {
                        if (it is ChatItem.Tool && it.id == id) it.copy(status = u.optString("status", it.status), output = out.ifEmpty { it.output }) else it
                    })
                }
                else -> s
            }
        }
    }
}
