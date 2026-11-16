package dev.syed.hermeslocal.engine

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import dev.syed.hermeslocal.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs the engine (rootfs) from a download (first run, update) or a file the user picked (offline).
 * Nothing is bundled in the APK since v1.0.0-test5: the payload would be stored twice (APK asset + tree).
 *
 * Download: resumable (HTTP Range into downloads/rootfs.part, with its url+sha in pending.json), verified
 * against sha256 before unpacking, deleted after unpacking. Unpack goes to r.partial and is swapped in only
 * when complete. On update only the user's data is carried over from the old /root (allow-list below), never
 * the old engine's dependency environment (/root/.hermes/installs) or caches.
 */
class EngineInstaller(private val context: Context) {
    private val paths = EnginePaths(context)

    fun installedInfo(): JSONObject? = try {
        JSONObject(paths.installedJson.readText())
    } catch (_: Exception) {
        null
    }

    fun defaultUrl(): String {
        val abi = if (BuildConfig.ENGINE_ABI == "x86_64") "x86_64" else "arm64"
        return "https://github.com/${BuildConfig.ENGINE_REPO}/releases/download/${BuildConfig.ENGINE_TAG}/rootfs-$abi.tar.zst"
    }

    /**
     * True when the installed engine came with an older app and this app expects another one: a bundled
     * engine (APKs before test5) or this app's default download with a different sha. Engines the user picked
     * (a URL or a file) are left alone.
     */
    fun needsUpgrade(): Boolean {
        if (!paths.isInstalled()) return false
        val info = installedInfo() ?: return true // installed without a record: an interrupted older install
        val expected = BuildConfig.ENGINE_SHA256
        return when (info.optString("source")) {
            "bundled" -> true
            SOURCE_DEFAULT -> expected.isNotEmpty() && info.optString("sha256") != expected
            else -> false
        }
    }

    /**
     * Leftovers of an install that was killed: r.partial (up to the whole tree) and finished or foreign
     * downloads. Keeps only the resumable part of [defaultUrl]-or-current download. Call when no install runs.
     */
    fun cleanLeftovers(): Long {
        var freed = 0L
        if (NativeFs.exists(paths.staging)) { freed += NativeFs.du(paths.staging); NativeFs.deleteTree(paths.staging) }
        val keep = setOf(paths.downloadPart.name, paths.pendingJson.name)
        paths.downloads.listFiles()?.forEach { f ->
            if (f.name !in keep || pendingFor() == null) { freed += NativeFs.du(f); NativeFs.deleteTree(f) }
        }
        return freed
    }

    /** url of the resumable download in downloads/, or null. */
    private fun pendingFor(): String? = runCatching { JSONObject(paths.pendingJson.readText()).getString("url") }.getOrNull()
        ?.takeIf { paths.downloadPart.isFile }

    /** Download [url] (resuming a partial download of the same url), verify, unpack, delete the archive. */
    suspend fun installFromUrl(url: String, onProgress: (stage: String, fraction: Float) -> Unit) = withContext(Dispatchers.IO) {
        paths.ensureDirs()
        val expected = if (url == defaultUrl() && BuildConfig.ENGINE_SHA256.isNotEmpty()) BuildConfig.ENGINE_SHA256
        else httpText("$url.sha256").trim().split(Regex("\\s+")).first().lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "No valid sha256 for $url" }
        val part = paths.downloadPart
        val pending = runCatching { JSONObject(paths.pendingJson.readText()) }.getOrNull()
        if (pending?.optString("url") != url || pending.optString("sha256") != expected) {
            part.delete()
            paths.pendingJson.writeText(JSONObject().put("url", url).put("sha256", expected).toString())
        }
        try {
            download(url, part) { f -> onProgress("Downloading engine", f) }
            val actual = part.inputStream().use { sha256(it, part.length()) { f -> onProgress("Verifying engine", f) } }
            if (actual != expected) throw IOException("Download is corrupt (sha256 mismatch); it will start over")
            part.inputStream().use { extractAndSwap(it, part.length(), expected, if (url == defaultUrl()) SOURCE_DEFAULT else url, onProgress) }
        } catch (e: IOException) {
            if (e.message?.contains("sha256") == true) part.delete()
            throw e
        }
        part.delete(); paths.pendingJson.delete()
    }

    /** Offline install from a payload the user picked (checked against this app's engine sha256). */
    suspend fun installFromFile(uri: Uri, onProgress: (stage: String, fraction: Float) -> Unit) = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val size = cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        val actual = cr.openInputStream(uri)?.use { sha256(it, size) { f -> onProgress("Verifying engine file", f) } }
            ?: throw IOException("Cannot read that file")
        val expected = BuildConfig.ENGINE_SHA256
        if (expected.isNotEmpty() && actual != expected)
            throw IOException("That file is not ${BuildConfig.ENGINE_TAG} (sha256 ${actual.take(12)}…, expected ${expected.take(12)}…)")
        cr.openInputStream(uri)!!.use { extractAndSwap(it, size, actual, if (actual == expected) SOURCE_DEFAULT else "file", onProgress) }
    }

    private suspend fun extractAndSwap(
        input: InputStream, size: Long, sha: String, source: String, onProgress: (String, Float) -> Unit,
    ) {
        paths.ensureDirs()
        // A slim engine unpacks to ~4.3x the zstd payload; the old tree stays until the swap.
        val free = StatFs(context.filesDir.absolutePath).availableBytes
        if (size > 0 && free < size * 5) throw IOException("Not enough free space: need about ${size * 5 / 1_048_576} MB, have ${free / 1_048_576} MB")
        NativeFs.deleteTree(paths.staging)
        val ctx = currentCoroutineContext()
        try {
            TarExtractor.extract(input, size, paths.staging, { f -> onProgress("Unpacking engine", f.toFloat()) }, { ctx.isActive })
        } catch (e: Throwable) {
            NativeFs.deleteTree(paths.staging); throw e
        }
        if (!File(paths.staging, "opt/hermes-local/bin/hermes-local-start").isFile) {
            NativeFs.deleteTree(paths.staging)
            throw IOException("Payload is not a Hermes Local engine")
        }
        onProgress("Moving your data over", -1f)
        carryUserData(paths.rootfs, paths.staging)
        onProgress("Finishing", -1f)
        NativeFs.deleteTree(paths.rootfs)
        NativeFs.move(paths.staging, paths.rootfs)
        File(paths.rootfs, "tmp").mkdirs()
        paths.installedJson.writeText(
            JSONObject().put("sha256", sha).put("source", source).put("tag", BuildConfig.ENGINE_TAG)
                .put("installedAt", System.currentTimeMillis()).toString()
        )
    }

    /** Deletes the engine and all Hermes data. */
    fun reset() {
        NativeFs.deleteTree(paths.staging)
        NativeFs.deleteTree(paths.rootfs)
        NativeFs.deleteTree(paths.downloads)
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
            val pct = if (size > 0) (done * 100 / size).toInt() else -1
            if (pct != last) { last = pct; onProgress(if (pct < 0) -1f else pct / 100f) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(url: String, from: Long = 0): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "HermesLocal")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399) {
                current = URL(current, c.getHeaderField("Location")); c.disconnect(); return@repeat
            }
            if (code != 200 && code != 206 && !(code == 416 && from > 0)) throw IOException("HTTP $code for $current")
            return c
        }
        throw IOException("Too many redirects for $url")
    }

    private fun httpText(url: String) = open(url).let { c -> c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() } }

    /** Appends to [dest] from its current length when the server honours Range (206), else starts over. */
    private suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val ctx = currentCoroutineContext()
        val have = if (dest.isFile) dest.length() else 0L
        val c = open(url, have)
        if (c.responseCode == 416) { c.disconnect(); return } // the part is already complete; the sha check decides
        val resumed = have > 0 && c.responseCode == 206
        val total = if (resumed) have + c.contentLengthLong else c.contentLengthLong
        if (have > 0 && total == have) { c.disconnect(); return } // already complete
        var done = if (resumed) have else 0L
        var last = -1
        try {
            c.inputStream.use { input ->
                FileOutputStream(dest, resumed).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != last) { last = pct; onProgress(if (pct < 0) -1f else pct / 100f) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (total > 0 && done != total) throw IOException("Download interrupted at ${done / 1_048_576} of ${total / 1_048_576} MB; it resumes next time")
    }

    companion object {
        const val SOURCE_DEFAULT = "default"

        /** What an engine update carries from the old /root/.hermes: the user's data, never the engine's
         *  dependency environment (installs/), caches, logs or Hermes' own checkout. */
        val HERMES_HOME_KEEP = setOf(
            "config.yaml", ".env", "auth.json", ".anthropic_oauth.json", "SOUL.md", "honcho.json",
            "state.db", "state.db-wal", "state.db-shm", "projects.db", "response_store.db", "runs_idempotency.db",
            "verification_evidence.db", "sessions", "memories", "skills", "cron", "plugins", "plugin-data", "hooks",
            "scripts", "profiles", "pairing", "checkpoints", "skins", "dashboard-themes", "pets", "session-exports",
            "mcp-installs", "images", "pastes", "workspace", "environments",
        )
        /** Top-level /root entries that belong to the engine (everything else there is the user's). */
        val ROOT_ENGINE_OWNED = setOf(".hermes", ".local", ".cache", ".npm", ".bashrc", ".profile")
    }

    /**
     * Moves the user's data from [oldRoot]/root into [newRoot]/root (both host paths of a rootfs):
     * /root/.hermes by allow-list ([HERMES_HOME_KEEP]; plugins merge so the engine's own plugins stay new),
     * every other /root entry except the engine-owned ones ([ROOT_ENGINE_OWNED]): .hermes-local, .codex,
     * .gemini (agy login), the user's files. Also the link2symlink store, which carried symlinks may point into.
     */
    private fun carryUserData(oldRootfs: File, newRootfs: File) {
        val oldRoot = File(oldRootfs, "root")
        if (!File(oldRoot, ".hermes/config.yaml").isFile) return
        val newRoot = File(newRootfs, "root")
        val oldHome = File(oldRoot, ".hermes")
        val newHome = File(newRoot, ".hermes").apply { mkdirs() }
        oldHome.list()?.forEach { name ->
            if (name !in HERMES_HOME_KEEP) return@forEach
            val from = File(oldHome, name)
            val to = File(newHome, name)
            if (name == "plugins" && to.isDirectory) {
                from.list()?.forEach { p -> if (!NativeFs.exists(File(to, p))) NativeFs.move(File(from, p), File(to, p)) }
            } else NativeFs.move(from, to)
        }
        oldRoot.list()?.forEach { name ->
            if (name !in ROOT_ENGINE_OWNED) NativeFs.move(File(oldRoot, name), File(newRoot, name))
        }
        val l2s = File(oldRootfs, ".l2s")
        if (NativeFs.exists(l2s)) NativeFs.move(l2s, File(newRootfs, ".l2s"))
    }
}
