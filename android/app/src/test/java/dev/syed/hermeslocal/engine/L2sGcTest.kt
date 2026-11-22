package dev.syed.hermeslocal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * proot link2symlink store layout, as termux/proot writes it with PROOT_L2S_DIR: link names are symlinks to
 * `<l2s>/.l2s.<name><NNNN>` (intermediate), which is a symlink to `<l2s>/.l2s.<name><NNNN>.<count>` (the file).
 */
class L2sGcTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun chain(l2s: File, name: String, count: Int = 2): File {
        val final = File(l2s, ".l2s.${name}0001.%04d".format(count)).apply { writeText("x".repeat(100)) }
        val intermediate = File(l2s, ".l2s.${name}0001")
        Files.createSymbolicLink(intermediate.toPath(), final.toPath()) // absolute host path, like proot
        return intermediate
    }

    private fun link(at: File, intermediate: File) {
        at.parentFile.mkdirs()
        Files.createSymbolicLink(at.toPath(), intermediate.toPath())
    }

    @Test fun keepsChainsInUseAndFindsOrphans() {
        val r = tmp.newFolder("r"); val l2s = File(r, ".l2s").apply { mkdirs() }
        val live = File(r, "root/.hermes/installs/k/environments/live")
        val stale = File(r, "root/.hermes/installs/k/environments/stale")
        val used = chain(l2s, "six.py")
        val usedByStaleOnly = chain(l2s, "attrs.py")
        val unused = chain(l2s, "orphan.py")
        link(File(live, "lib/six.py"), used)
        link(File(stale, "lib/six.py"), used)
        link(File(stale, "lib/attrs.py"), usedByStaleOnly)
        // Seen under the other spelling of the app's files dir (/data/user/0 vs /data/data): matched by name.
        val other = File(r, "root/.cache/uv/x.py"); other.parentFile.mkdirs()
        Files.createSymbolicLink(other.toPath(), File("/data/data/dev.syed.hermeslocal/files/r/.l2s/.l2s.cached.py0001").toPath())
        val cached = chain(l2s, "cached.py")
        // A final whose intermediate is gone (broken chain), and something of another shape: kept.
        val brokenFinal = File(l2s, ".l2s.broken.py0003.0002").apply { writeText("y") }
        val unknown = File(l2s, "notes.txt").apply { writeText("z") }
        // Ordinary rootfs symlinks (absolute, pointing outside) are never followed.
        Files.createSymbolicLink(File(r, "proc-link").toPath(), File("/proc").toPath())

        val all = L2sGc.find(r, l2s)!!
        val names = all.orphans.map { it.name }.toSet()
        val unusedFinal = Files.readSymbolicLink(unused.toPath()).fileName.toString()
        assertEquals(setOf(unused.name, unusedFinal, brokenFinal.name), names)
        assertEquals(3, all.keptChains) // six, attrs (the stale generation is still on disk), cached

        // Clean up's estimate: the stale generation is about to go, so what only it used counts as freed too.
        val planned = L2sGc.find(r, l2s, listOf(stale))!!.orphans.map { it.name }.toSet()
        assertTrue(usedByStaleOnly.name in planned)
        assertTrue(used.name !in planned && cached.name !in planned)

        // After deleting: the live generation's link still resolves, nothing in use went.
        assertEquals(names.size, L2sGc.delete(all.orphans))
        assertEquals("x".repeat(100), File(live, "lib/six.py").readText())
        assertEquals("x".repeat(100), File(stale, "lib/attrs.py").readText())
        assertTrue(unknown.exists())
        assertTrue(L2sGc.find(r, l2s)!!.orphans.isEmpty())
    }

    @Test fun emptyOrMissingStore() {
        val r = tmp.newFolder("r")
        assertTrue(L2sGc.find(r, File(r, ".l2s"))!!.orphans.isEmpty())
        File(r, ".l2s").mkdirs()
        assertTrue(L2sGc.find(r, File(r, ".l2s"))!!.orphans.isEmpty())
    }

    @Test fun anUnreadableDirectoryMeansNoGuess() {
        assumeFalse("root reads every directory", System.getProperty("user.name") == "root")
        val r = tmp.newFolder("r"); val l2s = File(r, ".l2s").apply { mkdirs() }
        chain(l2s, "a.py")
        val locked = File(r, "root/locked").apply { mkdirs() }
        locked.setReadable(false, false); locked.setExecutable(false, false)
        try {
            assumeFalse("this filesystem ignores permissions", locked.list() != null)
            assertNull(L2sGc.find(r, l2s)) // a reference may hide in there: report nothing
        } finally {
            locked.setReadable(true, false); locked.setExecutable(true, false)
        }
    }
}
