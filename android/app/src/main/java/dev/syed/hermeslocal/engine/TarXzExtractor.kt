package dev.syed.hermeslocal.engine

import android.system.ErrnoException
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Streams a .tar.xz rootfs to disk (never into memory), as README-ENGINE.md asks:
 *  - symlinks are created exactly as stored with Os.symlink; absolute targets are right, proot
 *    resolves them inside the rootfs;
 *  - mode bits are applied with Os.chmod; owners (all 0) are ignored, proot -0 fakes root;
 *  - the payload is packed with --hard-dereference, so there are no hard links; one that appears
 *    anyway is copied, since the app's SELinux domain cannot link();
 *  - device nodes and fifos are skipped (/dev, /proc, /sys are bound from the host).
 */
internal object TarXzExtractor {
  private const val MODE_MASK = 0xFFF // 07777
  private const val OWNER_RW = 0x180 // 0600
  private const val OWNER_RWX = 0x1C0 // 0700
  private const val DIR_DEFAULT = 0x1ED // 0755

  class Result(val files: Int, val symlinks: Int, val bytes: Long)

  /** [compressedSize] drives progress; pass -1 when unknown (progress then stays null-ish at 0). */
  fun extract(
    source: InputStream,
    compressedSize: Long,
    dest: File,
    onProgress: (fraction: Double) -> Unit,
    shouldContinue: () -> Boolean,
  ): Result {
    dest.mkdirs()
    val counting = CountingInputStream(BufferedInputStream(source, 1 shl 16))
    val dirModes = ArrayList<Pair<File, Int>>()
    var files = 0
    var symlinks = 0
    var bytes = 0L
    var lastReported = -1
    // xz -9 uses a 64 MiB dictionary; -1 = no memory limit (the decoder needs ~65 MiB).
    XZInputStream(counting, -1).use { xz ->
      TarArchiveInputStream(BufferedInputStream(xz, 1 shl 16)).use { tar ->
        while (true) {
          if (!shouldContinue()) throw InterruptedException("extraction cancelled")
          @Suppress("DEPRECATION")
          val entry = tar.nextTarEntry ?: break
          val relative = sanitize(entry.name) ?: continue
          val target = File(dest, relative)
          val mode = entry.mode and MODE_MASK
          when {
            entry.isDirectory -> {
              mkdirsNoFollow(target)
              dirModes.add(target to mode)
            }
            entry.isSymbolicLink -> {
              target.parentFile?.let { mkdirsNoFollow(it) }
              replace(target)
              Os.symlink(entry.linkName, target.path)
              symlinks++
            }
            entry.isLink -> {
              val linkSource = File(dest, sanitize(entry.linkName) ?: throw IOException("bad hard link ${entry.name}"))
              target.parentFile?.let { mkdirsNoFollow(it) }
              replace(target)
              NativeFs.copyTree(linkSource, target)
              files++
            }
            entry.isFile -> {
              target.parentFile?.let { mkdirsNoFollow(it) }
              replace(target)
              target.outputStream().use { out -> bytes += tar.copyTo(out, 1 shl 16) }
              Os.chmod(target.path, mode or OWNER_RW)
              files++
            }
            else -> Unit
          }
          if (compressedSize > 0) {
            val percent = ((counting.count * 100) / compressedSize).toInt()
            if (percent != lastReported) {
              lastReported = percent
              onProgress((counting.count.toDouble() / compressedSize).coerceAtMost(1.0))
            }
          }
        }
      }
    }
    // Directory modes last, so read-only directories did not block their own entries.
    for ((dir, mode) in dirModes.asReversed()) {
      try {
        Os.chmod(dir.path, mode or OWNER_RWX)
      } catch (_: ErrnoException) {
      }
    }
    onProgress(1.0)
    return Result(files, symlinks, bytes)
  }

  /** The entry's path inside the root, or null for the root itself. Rejects `..`. */
  private fun sanitize(name: String): String? {
    val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.isEmpty()) return null
    if (parts.any { it == ".." }) throw IOException("path escapes the rootfs: $name")
    return parts.joinToString("/")
  }

  private fun replace(target: File) {
    if (NativeFs.exists(target)) NativeFs.deleteTree(target)
  }

  private fun mkdirsNoFollow(dir: File) {
    if (NativeFs.exists(dir)) return
    dir.parentFile?.let { mkdirsNoFollow(it) }
    try {
      Os.mkdir(dir.path, DIR_DEFAULT)
    } catch (e: ErrnoException) {
      if (!dir.isDirectory) throw IOException("mkdir ${dir.path}: ${e.message}", e)
    }
  }

  private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
      private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
      super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
  }
}
