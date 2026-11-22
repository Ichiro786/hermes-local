package dev.syed.hermeslocal.engine

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Orphans in proot's link2symlink store (PROOT_L2S_DIR = <rootfs>/.l2s).
 *
 * proot fakes a hard link by moving the file to `<l2s>/.l2s.<name><NNNN>.<count>` (the "final" file, <count> =
 * number of links) behind a symlink `<l2s>/.l2s.<name><NNNN>` (the "intermediate"), and turning every link name
 * into a symlink to the intermediate. Removing a link *inside proot* lowers the count and deletes both at zero;
 * Storage's Clean up deletes unused dependency generations from Android, outside proot, so the counts never drop
 * and the store keeps their files (test6 known issue: ~400 MB per plugin install from the second one on).
 *
 * A chain is an orphan when no symlink anywhere in the rootfs points to its intermediate. Conservative by design:
 * a reference is matched by the intermediate's file name only (whatever the path spelling: /data/user/0 or
 * /data/data, absolute or relative), entries of any other shape are kept, and any error while walking the rootfs
 * means no orphans are reported at all.
 */
object L2sGc {
    private const val PREFIX = ".l2s."
    private val INTERMEDIATE = Regex("""^\.l2s\..*\d{4}$""")
    private val FINAL = Regex("""^(\.l2s\..*\d{4})\.\d{4,9}$""")

    data class Result(
        /** Intermediates and finals nothing refers to any more. */
        val orphans: List<File>,
        /** Chains still referenced (kept). */
        val keptChains: Int,
        val orphanChains: Int,
    )

    /**
     * Scans [rootfs] (never following symlinks, skipping [l2sDir] itself and the trees in [ignore], which are
     * about to be deleted) and returns the orphans of [l2sDir], or null when that cannot be told for sure.
     */
    fun find(rootfs: File, l2sDir: File, ignore: Collection<File> = emptyList()): Result? {
        val l2s = l2sDir.toPath()
        if (!Files.isDirectory(l2s, LinkOption.NOFOLLOW_LINKS)) return Result(emptyList(), 0, 0)
        val entries = l2sDir.list() ?: return null
        if (entries.isEmpty()) return Result(emptyList(), 0, 0)

        // (Not `list + path`: a Path is an Iterable of its name elements, so that would add "tmp", "r", ".l2s".)
        val skip = HashSet<Path>().apply {
            ignore.forEach { add(it.toPath().toAbsolutePath().normalize()) }
            add(l2s.toAbsolutePath().normalize())
        }
        val referenced = HashSet<String>()
        var failed = false
        try {
            Files.walkFileTree(rootfs.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir.toAbsolutePath().normalize() in skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink) {
                        val name = try {
                            Files.readSymbolicLink(file).fileName?.toString()
                        } catch (_: IOException) {
                            failed = true; return FileVisitResult.TERMINATE
                        }
                        if (name != null && name.startsWith(PREFIX)) referenced += name
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    failed = true
                    return FileVisitResult.TERMINATE
                }
            })
        } catch (_: Exception) {
            return null
        }
        if (failed) return null

        val names = entries.toHashSet()
        val orphanIntermediates = HashSet<String>()
        var kept = 0
        for (n in entries) {
            if (!INTERMEDIATE.matches(n) || !Files.isSymbolicLink(File(l2sDir, n).toPath())) continue
            if (n in referenced) kept++ else orphanIntermediates += n
        }
        val orphans = ArrayList<File>()
        orphanIntermediates.forEach { orphans += File(l2sDir, it) }
        for (n in entries) {
            val m = FINAL.matchEntire(n) ?: continue
            val intermediate = m.groupValues[1]
            if (n in referenced) continue // something points at the final itself: keep
            // Reached only through its intermediate: an orphan when that is an orphan or gone (a broken chain).
            if (intermediate in orphanIntermediates || intermediate !in names) {
                if (Files.isRegularFile(File(l2sDir, n).toPath(), LinkOption.NOFOLLOW_LINKS)) orphans += File(l2sDir, n)
            }
        }
        return Result(orphans, kept, orphanIntermediates.size)
    }

    /** Deletes [files] (no symlink is followed); returns how many went. */
    fun delete(files: List<File>): Int = files.count { f -> runCatching { Files.deleteIfExists(f.toPath()) }.getOrDefault(false) }
}
