package run.hermes.empryo.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

/** Pure logic behind the test2 fixes (Robolectric only for the real org.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LogicTest {
    @Test fun acpErrorShowsEmpryoDetails() {
        // Captured from `empryo acp` 3.12.8-beta against a stub provider answering HTTP 400.
        val err = JSONObject("""{"code":-32603,"message":"Internal error","data":{"details":"Provider request failed (HTTP 400) from 127.0.0.1:18990: STUB_PROVIDER_ERROR: this model refused the request"}}""")
        assertEquals(
            "Internal error: Provider request failed (HTTP 400) from 127.0.0.1:18990: STUB_PROVIDER_ERROR: this model refused the request",
            AcpClient(ByteArrayOutputStream()).errorText(err),
        )
        assertEquals("Authentication required", AcpClient(ByteArrayOutputStream()).errorText(JSONObject("""{"code":-32000,"message":"Authentication required"}""")))
    }

    @Test fun resolvConfPutsNetworkFirstAndKeepsAFallback() {
        val order = GuestSetup.order(listOf(InetAddress.getByName("fe80::1"), InetAddress.getByName("2405:201::1"), InetAddress.getByName("192.168.29.1")))
        assertEquals("192.168.29.1", order[0])
        assertTrue(order[1].startsWith("2405:201:"))
        val conf = GuestSetup.resolvConf(listOf("192.168.29.1", "10.0.0.1", "10.0.0.2"))
        assertEquals(listOf("192.168.29.1", "10.0.0.1", "1.1.1.1"), conf.lines().filter { it.startsWith("nameserver") }.map { it.removePrefix("nameserver ") })
        val none = GuestSetup.resolvConf(emptyList())
        assertEquals(3, none.lines().count { it.startsWith("nameserver") })
        assertTrue(none.contains("options timeout:2 attempts:2"))
    }

    @Test fun pairingParsesServiceReplyAndConfirm() {
        val info = javaClass.classLoader!!.getResource("pair-info.json")!!.readText() // a real `start` reply from phone-service
        Pairing.onLine("""{"event":"welcome","version":12,"pid":1,"cwd":"/root/workspace","managed":true,"release":"3.12.8-beta","link":4}""")
        Pairing.onLine("""{"event":"reply","id":"start-1","ok":true,"data":$info}""")
        val u = Pairing.ui.value
        assertEquals("ready", u.state)
        assertTrue(u.link!!.startsWith("empryo://pair?c="))
        assertEquals(65, u.qr.size)
        assertTrue(u.reaches.single().startsWith("quick relay.empryo.com/"))
        Pairing.onLine("""{"event":"confirm","id":"ab12","request":{"fingerprint":"428 913","name":"Syed's iPhone","via":"wifi","kind":"phone"}}""")
        assertEquals("428 913", Pairing.ui.value.request!!.fingerprint)
        Pairing.onLine("""{"event":"confirm-settled","id":"ab12"}""")
        assertEquals(null, Pairing.ui.value.request)
    }

    @Test fun dropsTest2SocketLinksOnlyWhenTheRootfsPathIsNotCanonical() {
        val real = Files.createTempDirectory("r").toFile()
        val tmp = File(real, "tmp").apply { mkdirs() }
        val alias = File(real.parentFile, "alias-${real.name}").also { Files.createSymbolicLink(it.toPath(), real.toPath()) }
        val r = File(alias.path) // like /data/user/0/... -> /data/data/...
        val dead = File(tmp, "empryo-phone-x.sock").also { Files.createSymbolicLink(it.toPath(), File("${r.path}/.l2s/.l2s.empryo-p1-a.sock0001").toPath()) }
        val live = File(tmp, "empryo-p2-b.sock").also { Files.createSymbolicLink(it.toPath(), File("${real.canonicalPath}/.l2s/.l2s.empryo-p2-b.sock0001").toPath()) }
        val plain = File(tmp, "notes.txt").apply { writeText("x") }
        GuestSetup.dropLegacyL2sLinks(r)
        assertTrue(!Files.exists(dead.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(live.toPath()) && plain.isFile)
        GuestSetup.dropLegacyL2sLinks(real) // canonical rootfs: nothing to do
        assertTrue(Files.isSymbolicLink(live.toPath()))
        alias.delete(); real.deleteRecursively()
    }
}
