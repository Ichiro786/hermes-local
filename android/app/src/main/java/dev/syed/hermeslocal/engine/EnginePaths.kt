package dev.syed.hermeslocal.engine

import android.content.Context
import java.io.File

/** Engine files. The rootfs lives at `filesDir/r` (short: guest Unix socket paths are capped at 108 bytes). */
class EnginePaths(context: Context) {
    private val app = context.applicationContext

    val rootfs = File(app.filesDir, "r")
    val staging = File(app.filesDir, "r.partial")
    val root = File(app.filesDir, "engine")
    val downloads = File(root, "downloads")
    val logs = File(root, "logs")
    val installedJson = File(root, "installed.json")
    val prootTmp = File(app.cacheDir, "proot")

    val nativeLibDir = File(app.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibDir, "libproot.so")
    val loader = File(nativeLibDir, "libproot-loader.so")

    /** Host views of guest files. */
    val guestState get() = File(rootfs, "root/.hermes-local")
    val settingsEnv get() = File(guestState, "settings.env")
    val apiKey get() = File(guestState, "api-key")
    val engineVersion get() = File(rootfs, "opt/hermes-local/engine-version")
    val startScript get() = File(rootfs, "opt/hermes-local/bin/hermes-local-start")

    val engineLog get() = File(logs, "engine.log")
    val engineLogOld get() = File(logs, "engine.log.1")

    fun ensureDirs() {
        listOf(root, downloads, logs, prootTmp).forEach { it.mkdirs() }
        if (NativeFs.exists(rootfs)) File(rootfs, "tmp").mkdirs()
    }

    fun isInstalled(): Boolean = startScript.isFile

    fun guestFile(guestPath: String) = File(rootfs, guestPath.trimStart('/'))
}
