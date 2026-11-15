package run.hermes.empryo.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/** A device asking to pair: Allow only if it shows the same [fingerprint]. */
data class PairRequest(val id: String, val name: String, val via: String, val fingerprint: String, val computer: Boolean, val unverified: Boolean)

data class PairUi(
    /** off: no phone-service; starting: service up, helper not attached yet; ready: attached; failed. */
    val state: String = "off",
    val message: String? = null,
    val via: String = "quick",
    val code: String? = null,
    val link: String? = null,
    /** QR modules, one "0101…" string per row (Empryo's own high-ECC matrix for the app's scanner). */
    val qr: List<String> = emptyList(),
    val reaches: List<String> = emptyList(),
    val pairingOpen: Boolean = false,
    val openedAt: Long = 0L,
    val phones: List<String> = emptyList(),
    val request: PairRequest? = null,
    val log: List<String> = emptyList(),
)

/**
 * Headless /remote: the app is a "window" on Empryo's phone-service control socket, through
 * assets/pair-client.js running inside the rootfs (Bun from the Empryo binary, BUN_BE_BUN=1). The protocol is
 * Empryo 3.12.8's own: hello{token,ui:true} -> start{via} (reply: code, link, QR, reaches; opens a 10 minute
 * pairing window) -> pairing-view{visible} (renews the code while shown) -> event confirm{id,request} answered
 * with confirm{id,allow}. EngineService owns the helper process; the UI calls [open], [setVisible], [answer].
 */
object Pairing {
    private val _ui = MutableStateFlow(PairUi())
    val ui: StateFlow<PairUi> = _ui.asStateFlow()
    @Volatile private var stdin: OutputStream? = null
    @Volatile private var visible = false
    private var seq = 0
    /** Set by EngineService: show or clear the "allow pairing?" notification. */
    @Volatile var onRequest: (PairRequest?) -> Unit = {}

    const val GUEST_SCRIPT = "/opt/empryo-local/pair-client.js"
    private const val WINDOW_MS = 10 * 60_000L

    /** Copies the helper into the rootfs and starts it (BUN_BE_BUN makes the Empryo binary act as `bun`). */
    fun startHelper(paths: Paths): Process {
        val f = File(paths.rootfs, GUEST_SCRIPT.removePrefix("/"))
        f.parentFile?.mkdirs()
        paths.app.assets.open("pair-client.js").use { input -> f.outputStream().use { input.copyTo(it) } }
        return Proot.processBuilder(paths, listOf(Paths.EMPRYO, GUEST_SCRIPT), extraEnv = mapOf("BUN_BE_BUN" to "1")).start()
    }

    fun attached(out: OutputStream?) {
        stdin = out
        if (out == null) _ui.update { if (it.state == "ready") it.copy(state = "starting", request = null) else it }
    }

    fun setState(state: String, message: String? = null) {
        _ui.update { if (state == "off") PairUi(via = it.via, log = it.log) else it.copy(state = state, message = message) }
        if (state == "off") onRequest(null)
    }

    /** A fresh code and link (closes any older one), on [via]: "quick" (relay, works anywhere), "wifi" or "auto". */
    fun open(via: String = _ui.value.via) {
        _ui.update { it.copy(via = via, message = null) }
        send(JSONObject().put("op", "start").put("id", "start-${++seq}").put("via", via))
        if (visible) send(JSONObject().put("op", "pairing-view").put("id", "view-${++seq}").put("owner", "app").put("visible", true))
    }

    /** While the Pair screen shows, Empryo renews an expired code by itself. */
    fun setVisible(v: Boolean) {
        visible = v
        send(JSONObject().put("op", "pairing-view").put("id", "view-${++seq}").put("owner", "app").put("visible", v))
    }

    fun answer(allow: Boolean) {
        val r = _ui.value.request ?: return
        send(JSONObject().put("op", "confirm").put("id", r.id).put("allow", allow))
        _ui.update { it.copy(request = null) }
        note(if (allow) "Allowed ${r.name}" else "Denied ${r.name}")
        onRequest(null)
    }

    private fun send(o: JSONObject): Boolean = try {
        stdin?.let { synchronized(it) { it.write((o.toString() + "\n").toByteArray()); it.flush() } } != null
    } catch (_: Exception) {
        false
    }

    private fun note(s: String) = _ui.update { it.copy(log = (it.log + s).takeLast(30)) }

    /** One line from the helper (service events, replies, helper status). Returns a short log line or null. */
    fun onLine(line: String): String? {
        val m = runCatching { JSONObject(line) }.getOrNull() ?: return "pair: $line"
        when (m.optString("event")) {
            "welcome" -> {
                _ui.update { it.copy(state = "ready", message = null) }
                if (visible || _ui.value.code != null) open()
                return "pair: attached to phone-service ${m.optString("release")} (managed=${m.optBoolean("managed")})"
            }
            "helper-error" -> return "pair helper: ${m.optString("error")}"
            "reply" -> {
                if (!m.optBoolean("ok")) {
                    val e = m.optString("error")
                    _ui.update { it.copy(message = e) }
                    note("Empryo: $e")
                    return "pair: ${m.optString("id")} failed: $e"
                }
                val d = m.optJSONObject("data") ?: return null
                if (d.has("code")) { applyLink(d, opened = m.optString("id").startsWith("start")); return "pair: code ready, reaches ${_ui.value.reaches}" }
            }
            "pairing" -> m.optJSONObject("link")?.let { applyLink(it, opened = true) }
            "phones" -> _ui.update { it.copy(phones = phoneNames(m)) }
            "activity" -> m.optJSONObject("activity")?.let { a ->
                val s = listOfNotNull(a.optString("stage"), a.optString("name").takeIf { it.isNotBlank() }, a.optString("via").takeIf { it.isNotBlank() }, a.optString("reason").takeIf { it.isNotBlank() }).joinToString(" · ")
                note(s); return "pair: activity $s"
            }
            "confirm" -> {
                val q = m.optJSONObject("request") ?: return null
                val r = PairRequest(
                    m.optString("id"), q.optString("name", "A device"), q.optString("via"), q.optString("fingerprint"),
                    q.optString("kind") == "computer", q.optBoolean("unverified"),
                )
                _ui.update { it.copy(request = r) }
                onRequest(r)
                return "pair: ${r.name} asks to pair over ${r.via}"
            }
            "confirm-settled" -> if (_ui.value.request?.id == m.optString("id")) { _ui.update { it.copy(request = null) }; onRequest(null) }
        }
        return null
    }

    private fun applyLink(d: JSONObject, opened: Boolean) {
        val qr = d.optJSONObject("qr")?.optJSONArray("cells")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val reaches = d.optJSONArray("reaches")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { "${it.optString("kind")} ${it.optString("address")}" }
        }.orEmpty()
        _ui.update { s ->
            s.copy(
                code = d.optString("code").ifBlank { s.code }, link = d.optString("link").ifBlank { s.link },
                qr = qr.ifEmpty { s.qr }, reaches = reaches, pairingOpen = d.optBoolean("pairingOpen", s.pairingOpen),
                openedAt = if (opened && d.optString("code") != s.code) System.currentTimeMillis() else s.openedAt,
                phones = if (d.has("phones")) phoneNames(d) else s.phones,
            )
        }
    }

    private fun phoneNames(o: JSONObject) = o.optJSONArray("phones")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { "${it.optString("name")} (${it.optString("kind")}${it.optString("via").takeIf { v -> v.isNotBlank() }?.let { v -> ", $v" } ?: ""})" }
    }.orEmpty()

    fun expiresAt(u: PairUi) = if (u.openedAt > 0) u.openedAt + WINDOW_MS else 0L
}
