package dev.syed.hermeslocal.engine

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs the rootfs: from the payload bundled in the APK (assets/engine/rootfs.tar.xz) or from a URL
 * (engine update). Always verifies sha256 before extracting, extracts into r.partial and swaps it in
 * only when complete. On update the old rootfs's /root (Hermes config, logins, sessions) is carried over.
 */
class EngineInstaller(private val context: Context) {
    private val paths = EnginePaths(context)

    fun bundledAvailable(): Boolean = try {
        context.assets.open(ASSET).close(); true
    } catch (_: IOException) {
        false
    }

    private fun bundledSha(): String = context.assets.open("$ASSET.sha256").bufferedReader().use { it.readText() }
        .trim().split(Regex("\\s+")).first().lowercase()

    fun installedInfo(): JSONObject? = try {
        JSONObject(paths.installedJson.readText())
    } catch (_: Exception) {
        null
    }

    /** True when the APK carries a different payload than the one installed (app update with new engine). */
    fun bundledDiffersFromInstalled(): Boolean {
        if (!bundledAvailable() || !paths.isInstalled()) return false
        val info = installedInfo() ?: return false
        return info.optString("source") == "bundled" && info.optString("sha256") != runCatching { bundledSha() }.getOrNull()
    }

    suspend fun installBundled(onProgress: (stage: String, fraction: Float) -> Unit) = withContext(Dispatchers.IO) {
        val expected = bundledSha()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "Bundled payload has no valid sha256" }
        val size = context.assets.openFd(ASSET).use { it.length }
        onProgress("Verifying engine", 0f)
        val actual = context.assets.open(ASSET).use { sha256(it, size) { f -> onProgress("Verifying engine", f) } }
        if (actual != expected) throw IOException("Bundled engine is corrupt (sha256 mismatch)")
        context.assets.open(ASSET).use { extractAndSwap(it, size, expected, "bundled", onProgress) }
    }

    /** Engine update from [url] (its sha256 from [url].sha256). */
    suspend fun installFromUrl(url: String, onProgress: (stage: String, fraction: Float) -> Unit) = withContext(Dispatchers.IO) {
        paths.ensureDirs()
        val expected = httpText("$url.sha256").trim().split(Regex("\\s+")).first().lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "No valid sha256 at $url.sha256" }
        val file = File(paths.downloads, "rootfs.tar.xz")
        download(url, file) { f -> onProgress("Downloading engine", f) }
        val actual = file.inputStream().use { sha256(it, file.length()) { f -> onProgress("Verifying engine", f) } }
        if (actual != expected) { file.delete(); throw IOException("Download is corrupt (sha256 mismatch)") }
        try {
            file.inputStream().use { extractAndSwap(it, file.length(), expected, url, onProgress) }
        } finally {
            file.delete()
        }
    }

    private suspend fun extractAndSwap(
        input: InputStream, size: Long, sha: String, source: String, onProgress: (String, Float) -> Unit,
    ) {
        paths.ensureDirs()
        val free = StatFs(context.filesDir.absolutePath).availableBytes
        if (free < size * 5) throw IOException("Not enough free space: need about ${size * 5 / 1_048_576} MB, have ${free / 1_048_576} MB")
        NativeFs.deleteTree(paths.staging)
        val ctx = currentCoroutineContext()
        TarXzExtractor.extract(input, size, paths.staging, { f -> onProgress("Unpacking engine", f.toFloat()) }, { ctx.isActive })
        if (!File(paths.staging, "opt/hermes-local/bin/hermes-local-start").isFile) {
            NativeFs.deleteTree(paths.staging)
            throw IOException("Payload is not a Hermes Local engine")
        }
        onProgress("Finishing", -1f)
        // Keep the user's data (/root: Hermes config, provider logins, sessions) across engine updates.
        val oldRoot = File(paths.rootfs, "root")
        if (NativeFs.exists(oldRoot) && File(oldRoot, ".hermes/config.yaml").isFile) {
            NativeFs.move(oldRoot, File(paths.staging, "root"))
        }
        NativeFs.deleteTree(paths.rootfs)
        NativeFs.move(paths.staging, paths.rootfs)
        File(paths.rootfs, "tmp").mkdirs()
        paths.installedJson.writeText(
            JSONObject().put("sha256", sha).put("source", source).put("installedAt", System.currentTimeMillis()).toString()
        )
    }

    /** Deletes the engine and all Hermes data. */
    fun reset() {
        NativeFs.deleteTree(paths.staging)
        NativeFs.deleteTree(paths.rootfs)
        paths.installedJson.delete()
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
            c.setRequestProperty("User-Agent", "HermesLocal")
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
    }

    companion object {
        const val ASSET = "engine/rootfs.tar.xz"
    }
}
