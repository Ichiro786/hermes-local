package dev.syed.hermeslocal.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sign-in output parser against real captured engine output (src/test/resources/captures):
 *  - codex-device-*: Hermes v0.23.0 `hermes auth add openai-codex --type oauth --no-browser`, raw, with ANSI colour
 *  - codex-helper-*: the same through hermes_local_codex_login.py (HL_* markers)
 *  - agy-login-*: agy 1.5.1 driven by hermes_local_agy_login.py, including a rejected code and the retry
 *  - captures from the engine's arm64/x86_64 smoke runs (chroot and termux-proot), when present
 */
class SignInParserTest {
    private fun capture(name: String): List<String> {
        val f = File("src/test/resources/captures/$name")
        return (if (f.exists()) f else File("app/src/test/resources/captures/$name")).readLines()
    }

    private fun events(lines: List<String>): List<SignInParser.Event> {
        val p = SignInParser()
        return lines.mapNotNull { p.feed(it) }
    }

    private val codeRe = Regex("^[A-Z0-9]{4}-[A-Z0-9]{5}$")

    @Test fun rawHermesCodexOutputWithAnsi() {
        val ev = events(capture("codex-device-v0.23.0-sandbox-arm64.txt"))
        val urls = ev.filterIsInstance<SignInParser.Event.Url>()
        val codes = ev.filterIsInstance<SignInParser.Event.Code>()
        assertEquals(listOf("https://auth.openai.com/codex/device"), urls.map { it.url })
        assertEquals(1, codes.size)
        assertTrue(codes[0].code, codeRe.matches(codes[0].code))
        // The test1 bug: the URL must not carry the ANSI reset (ESC[0m) or any control byte.
        assertFalse(urls[0].url.any { it.isISOControl() || it == '[' })
        // The code comes after the URL (the app opens the browser only once both are shown).
        assertTrue(ev.indexOf(urls[0]) < ev.indexOf(codes[0]))
    }

    /** The v1.0.0-test1 regexes on the same capture: the URL kept ESC[0m and no code was found. */
    @Test fun test1ParserBugReproduced() {
        val oldUrl = Regex("""https?://[^\s'"<>]+""")
        val oldCode = Regex("""\b[A-Z0-9]{4,}-[A-Z0-9]{4,}\b""")
        var url: String? = null
        var code: String? = null
        for (line in capture("codex-device-v0.23.0-sandbox-arm64.txt")) {
            url = url ?: oldUrl.find(line)?.value?.trimEnd('.', ',', ')')
            code = code ?: if (line.contains("code", ignoreCase = true)) oldCode.find(line)?.value else null
        }
        assertEquals("https://auth.openai.com/codex/device\u001b[0m", url)
        assertNull(code)
    }

    @Test fun codexHelperMarkers() {
        val ev = events(capture("codex-helper-v0.23.0-sandbox-arm64.txt"))
        assertEquals("https://auth.openai.com/codex/device", ev.filterIsInstance<SignInParser.Event.Url>().single().url)
        assertTrue(codeRe.matches(ev.filterIsInstance<SignInParser.Event.Code>().single().code))
        assertTrue(ev.none { it is SignInParser.Event.Done })
    }

    @Test fun agyUrlPromptErrorAndRetry() {
        val ev = events(capture("agy-login-1.5.1-vm-x86_64.txt"))
        val urls = ev.filterIsInstance<SignInParser.Event.Url>().map { it.url }
        assertEquals(2, urls.size) // first attempt, then the retry after the rejected code
        urls.forEach {
            assertTrue(it, it.startsWith("https://accounts.google.com/o/oauth2/auth?"))
            assertTrue(it, "redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback" in it)
            assertTrue(it, "&state=" in it) // the whole URL, not a piece of a wrapped line
        }
        assertEquals(2, ev.count { it == SignInParser.Event.Prompt })
        val err = ev.filterIsInstance<SignInParser.Event.Error>().single()
        assertTrue(err.message, "invalid_grant" in err.message)
    }

    @Test fun engineSmokeCaptures() {
        // Captures copied from the engine workflow's smoke runs (arm64 = the phone's engine).
        val dir = listOf(File("src/test/resources/captures"), File("app/src/test/resources/captures")).first { it.exists() }
        val smoke = dir.listFiles { f -> f.name.startsWith("smoke-") }.orEmpty()
        for (f in smoke.filter { "codex" in it.name }) {
            val ev = events(f.readLines())
            assertEquals(f.name, "https://auth.openai.com/codex/device", ev.filterIsInstance<SignInParser.Event.Url>().first().url)
            assertTrue(f.name, codeRe.matches(ev.filterIsInstance<SignInParser.Event.Code>().first().code))
        }
        for (f in smoke.filter { "agy" in it.name }) {
            val ev = events(f.readLines())
            assertTrue(f.name, ev.filterIsInstance<SignInParser.Event.Url>().first().url.startsWith("https://accounts.google.com/"))
            assertTrue(f.name, ev.contains(SignInParser.Event.Prompt))
        }
    }

    @Test fun markersAndEdgeCases() {
        val p = SignInParser()
        assertEquals(SignInParser.Event.Done(true, "token saved"), p.feed("HL_DONE ok token saved"))
        assertEquals(SignInParser.Event.Done(false, "exit 1: Login failed"), p.feed("HL_DONE fail exit 1: Login failed"))
        assertNull(p.feed("\u001b[0m   "))
        assertEquals(SignInParser.Event.Prompt, p.feed("HL_PROMPT code"))
        // A code-looking line with no URL before it and no "Enter this code" is just text.
        assertTrue(SignInParser().feed("ABCD-EFGHI") is SignInParser.Event.Line)
        assertNull(SignInParser.cleanUrl("no url here"))
        assertEquals("https://auth.openai.com/codex/device", SignInParser.cleanUrl("\u001b[94mhttps://auth.openai.com/codex/device\u001b[0m"))
    }
}
