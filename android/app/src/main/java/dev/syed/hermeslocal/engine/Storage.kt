package dev.syed.hermeslocal.engine

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * What the app's data is made of, and the safe cleanup (engine stopped): dependency generations Hermes no
 * longer selects (every plugin/extra install leaves the old one behind, ~350 MB each), Hermes' download caches
 * (uv ~475 MB per install), agy self-update leftovers, an interrupted install or download, old log rotations.
 */
class Storage(context: Context) {
    private val app = context.applicationContext
    private val paths = EnginePaths(app)

    data class Part(val label: String, val bytes: Long, val detail: String = "")
    data class Report(val total: Long, val parts: List<Part>, val cleanable: Long)

    private val home get() = File(paths.rootfs, "root/.hermes")
    private val agyOld get() = listOfNotNull(
        File(paths.rootfs, "usr/local/bin").listFiles(),
        File(paths.rootfs, "root/.local/bin").listFiles(),
    ).flatMap { it.toList() }.filter { it.name.startsWith("agy.") && it.name.endsWith(".old") }

    /** Dependency environments facts.json no longer selects, across every pm store (installs/<key>/). */
    private fun staleEnvironments(): List<File> =
        (File(home, "installs").listFiles() ?: emptyArray()).flatMap { store ->
            val keep = selectedEnvironment(runCatching { File(store, "facts.json").readText() }.getOrNull())
                ?: return@flatMap emptyList() // unknown selection: never guess
            (File(store, "environments").listFiles() ?: emptyArray()).filter { it.name != keep }
        }

    private fun cleanTargets(): List<File> = staleEnvironments() + agyOld + listOf(
        File(home, "cache"), File(paths.rootfs, "root/.cache"), paths.staging, paths.downloads, paths.engineLogOld,
    )

    fun scan(): Report {
        val installs = NativeFs.du(File(home, "installs"))
        val stale = staleEnvironments()
        val sessions = listOf("state.db", "state.db-wal", "state.db-shm", "sessions", "memories").sumOf { NativeFs.du(File(home, it)) }
        val caches = NativeFs.du(File(home, "cache")) + NativeFs.du(File(paths.rootfs, "root/.cache"))
        val agy = NativeFs.du(File(paths.rootfs, "usr/local/bin/agy")) + agyOld.sumOf { NativeFs.du(it) }
        val logs = NativeFs.du(paths.logs) + NativeFs.du(File(home, "logs"))
        val leftovers = NativeFs.du(paths.staging) + NativeFs.du(paths.downloads)
        val rootfs = NativeFs.du(paths.rootfs)
        val engine = rootfs - installs - sessions - caches - agy - NativeFs.du(File(home, "logs"))
        val parts = listOf(
            Part("Hermes engine", engine, "Ubuntu userland, Hermes Agent, tools"),
            Part("Python dependencies", installs, if (installs == 0L) "" else if (stale.isEmpty()) "one generation, in use" else "${stale.size} unused generation(s) left by installs"),
            Part("Sessions and memory", sessions),
            Part("Antigravity CLI", agy, if (agyOld.isEmpty()) "" else "${agyOld.size} old copy left by a self-update"),
            Part("Caches", caches, "downloads Hermes keeps after installs"),
            Part("Logs", logs),
            Part("Leftovers", leftovers, "interrupted install or download"),
        )
        val total = NativeFs.du(app.filesDir) + NativeFs.du(app.cacheDir)
        return Report(total, parts, cleanTargets().sumOf { NativeFs.du(it) })
    }

    /** Deletes [cleanTargets]; the engine must be stopped (Hermes may be using a cache or an environment). */
    fun cleanUp(): Long {
        val targets = cleanTargets()
        val freed = targets.sumOf { NativeFs.du(it) }
        targets.forEach { NativeFs.deleteTree(it) }
        return freed
    }

    companion object {
        /** The environment id facts.json selects (`packages.venv.environment` = .../environments/<id>/venv). */
        fun selectedEnvironment(factsJson: String?): String? = runCatching {
            val env = JSONObject(factsJson!!).getJSONObject("packages").getJSONObject("venv").getString("environment")
            env.trimEnd('/').split('/').dropLast(1).last().takeIf { it.isNotBlank() && env.trimEnd('/').endsWith("/venv") }
        }.getOrNull()
    }
}
