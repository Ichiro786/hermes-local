package run.hermes.empryo.engine

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import run.hermes.empryo.BuildConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * First run, two parts:
 *  1. the minimal rootfs bundled in the APK (assets/rootfs/rootfs.tar.xz: Ubuntu minbase + git + ca-certs),
 *  2. Empryo itself, downloaded from dl.empryo.com and checked against the sha256 pinned at build time
 *     (and the release's SHA256SUMS.txt when reachable), unpacked to /opt/empryo, lazygit dropped.
 */
class Installer(private val context: Context) {
    private val paths = Paths(context)

    private fun bundledSha(): String = context.assets.open("$ASSET.sha256").bufferedReader().use { it.readText() }
        .trim().split(Regex("\\s+")).first().lowercase()

    fun rootfsCurrent(): Boolean = paths.rootfsInstalled() &&
        runCatching { paths.rootfsStamp.readText().trim() == bundledSha() }.getOrDefault(false)

    suspend fun installRootfs(onProgress: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        paths.ensureDirs()
        val expected = bundledSha()
        val size = context.assets.openFd(ASSET).use { it.length }
        onProgress("Checking base system", 0f)
        val actual = context.assets.open(ASSET).use { sha256(it, size) { f -> onProgress("Checking base system", f) } }
        if (actual != expected) throw IOException("Bundled rootfs is corrupt (sha256 mismatch)")
        requireFree(size * 8)
        NativeFs.deleteTree(paths.staging)
        val ctx = currentCoroutineContext()
        context.assets.open(ASSET).use {
            TarExtractor.extract(it, size, paths.staging, { f -> onProgress("Unpacking base system", f.toFloat()) }, { ctx.isActive })
        }
        // Keep the user's data and the downloaded engine across a base-system update.
        for (keep in listOf("root", "opt/empryo")) {
            val old = File(paths.rootfs, keep)
            if (NativeFs.exists(old)) NativeFs.move(old, File(paths.staging, keep))
        }
        NativeFs.deleteTree(paths.rootfs)
        NativeFs.move(paths.staging, paths.rootfs)
        paths.ensureDirs()
        paths.rootfsStamp.writeText(expected)
    }

    suspend fun installEngine(onProgress: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        paths.ensureDirs()
        val expected = BuildConfig.EMPRYO_SHA256
        // The release's own list must agree with the pin when we can read it (a mismatch means a re-published build).
        runCatching { httpText(BuildConfig.EMPRYO_SUMS_URL) }.getOrNull()?.lineSequence()
            ?.firstOrNull { it.trim().endsWith("  ${BuildConfig.EMPRYO_ASSET}") }?.substringBefore(' ')?.lowercase()
            ?.let { if (it != expected) throw IOException("SHA256SUMS.txt lists $it for ${BuildConfig.EMPRYO_ASSET}, this build pins $expected") }
        requireFree(900L shl 20)
        val file = File(paths.downloads, BuildConfig.EMPRYO_ASSET)
        val label = "Downloading Empryo ${BuildConfig.EMPRYO_VERSION}"
        download(BuildConfig.EMPRYO_URL, file) { f -> onProgress(label, f) }
        try {
            val actual = file.inputStream().use { sha256(it, file.length()) { f -> onProgress("Verifying download", f) } }
            if (actual != expected) throw IOException("Download is corrupt (sha256 mismatch)")
            val stage = File(paths.rootfs, "opt/.empryo-partial")
            NativeFs.deleteTree(stage)
            val ctx = currentCoroutineContext()
            file.inputStream().use {
                TarExtractor.extract(it, file.length(), stage, { f -> onProgress("Unpacking Empryo", f.toFloat()) }, { ctx.isActive }, gzip = true)
            }
            // The tarball holds one top directory (empryo-<ver>-linux-<arch>/).
            val top = stage.listFiles()?.singleOrNull { File(it, "empryo").isFile } ?: throw IOException("Unexpected Empryo tarball layout")
            listOf("deps/lazygit", "install.sh", "uninstall.sh").forEach { NativeFs.deleteTree(File(top, it)) }
            NativeFs.deleteTree(paths.engineDir)
            NativeFs.move(top, paths.engineDir)
            NativeFs.deleteTree(stage)
            paths.engineStamp.writeText("${BuildConfig.EMPRYO_VERSION} $expected\n")
        } finally {
            file.delete()
        }
    }

    private fun requireFree(bytes: Long) {
        val free = StatFs(context.filesDir.absolutePath).availableBytes
        if (free < bytes) throw IOException("Not enough free space: need about ${bytes shr 20} MB, have ${free shr 20} MB")
    }

    private fun sha256(input: InputStream, size: Long, onProgress: (Float) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var done = 0L
        var last = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            done += n
            val pct = if (size > 0) (done * 100 / size).toInt() else 0
            if (pct != last) { last = pct; onProgress(pct / 100f) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(5) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "EmpryoLocal")
            val code = c.responseCode
            if (code in 300..399) {
                current = URL(current, c.getHeaderField("Location")); c.disconnect(); return@repeat
            }
            if (code != 200) throw IOException("HTTP $code for $current")
            return c
        }
        throw IOException("Too many redirects for $url")
    }

    private fun httpText(url: String) = open(url).let { c -> c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() } }

    private fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val c = open(url)
        val total = c.contentLengthLong
        var done = 0L
        var last = -1
        c.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != last) { last = pct; onProgress(if (pct < 0) -1f else pct / 100f) }
                }
            }
        }
        c.disconnect()
        if (total > 0 && done != total) throw IOException("Download stopped at ${done shr 20} of ${total shr 20} MB")
    }

    companion object {
        const val ASSET = "rootfs/rootfs.tar.xz"
    }
}
