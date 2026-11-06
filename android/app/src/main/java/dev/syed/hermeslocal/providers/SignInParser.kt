package dev.syed.hermeslocal.providers

/**
 * Reads the output of `hermes-local-auth login <provider>` one line at a time.
 *
 * The engine helpers print marker lines (`HL_URL`, `HL_CODE`, `HL_PROMPT`, `HL_ERROR`, `HL_DONE`, `HL_MODEL`,
 * `HL_STATE`, `HL_RAW`). For engines without the markers it also understands Hermes' own device-code text:
 * the URL and the code each on their own line, wrapped in ANSI colour, after "Open this URL" / "Enter this
 * code" (Hermes v0.22/v0.23 `_codex_device_code_login`). ANSI is always stripped first; that was the
 * test1 bug (the URL kept `ESC[0m`, the code regex never matched).
 */
class SignInParser {
    sealed interface Event {
        data class Url(val url: String) : Event
        data class Code(val code: String) : Event
        data object Prompt : Event
        data class Error(val message: String) : Event
        data class Done(val ok: Boolean, val detail: String) : Event
        data class Model(val json: String) : Event
        data class State(val text: String) : Event
        data class Line(val text: String) : Event
    }

    private var expect: String? = null // "url" | "code": the next non-empty raw line carries it
    private var sawUrl = false

    fun feed(rawLine: String): Event? {
        val line = strip(rawLine).trim()
        if (line.isEmpty()) return null
        val marker = MARKER.matchEntire(line)
        if (marker != null) {
            val (tag, rest) = marker.destructured
            val value = rest.trim()
            return when (tag) {
                "URL" -> cleanUrl(value)?.let { sawUrl = true; Event.Url(it) } ?: Event.Line(value)
                "CODE" -> if (CODE.matches(value)) Event.Code(value) else Event.Line(value)
                "PROMPT" -> Event.Prompt
                "ERROR" -> Event.Error(value)
                "DONE" -> Event.Done(value.startsWith("ok"), value.substringAfter(' ', "").trim())
                "MODEL" -> Event.Model(value)
                "STATE" -> Event.State(value)
                else -> Event.Line(value)
            }
        }
        // Plain Hermes text (no markers).
        val low = line.lowercase()
        when {
            "open this url" in low -> { expect = "url"; return Event.Line(line) }
            "enter this code" in low -> { expect = "code"; return Event.Line(line) }
        }
        val url = URL.find(line)?.value?.let(::cleanUrl)
        if (url != null && (expect == "url" || "/device" in url)) {
            expect = null; sawUrl = true
            return Event.Url(url)
        }
        if (CODE.matches(line) && (expect == "code" || sawUrl)) {
            expect = null
            return Event.Code(line)
        }
        return Event.Line(line)
    }

    companion object {
        private val ANSI = Regex("\u001B\\[[0-9;?<>=]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[()][0-9A-Za-z]|\u001B[=>78DMEc]")
        private val MARKER = Regex("^HL_([A-Z]+)(?:\\s+(.*))?$")
        private val URL = Regex("https://[^\\s\"'<>\u001B]+")
        private val CODE = Regex("^[A-Z0-9]{4,}(?:-[A-Z0-9]{4,})+$")

        fun strip(s: String): String = ANSI.replace(s, "").replace("\r", "")

        /** An https URL with no control bytes or trailing punctuation, else null. */
        fun cleanUrl(s: String): String? {
            val u = URL.find(strip(s))?.value?.trimEnd('.', ',', ')') ?: return null
            return u.takeIf { it.none { c -> c.isISOControl() } }
        }
    }
}
