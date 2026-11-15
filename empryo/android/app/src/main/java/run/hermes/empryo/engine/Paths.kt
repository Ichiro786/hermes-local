package run.hermes.empryo.engine

import android.content.Context
import java.io.File

/** Engine files. The rootfs lives at `filesDir/r` (short: guest Unix socket paths are capped at 108 bytes). */
class Paths(context: Context) {
    private val app = context.applicationContext

    val rootfs = File(app.filesDir, "r")
    val staging = File(app.filesDir, "r.partial")
    val downloads = File(app.filesDir, "dl")
    val logs = File(app.filesDir, "logs")
    val prootTmp = File(app.cacheDir, "proot")

    val nativeLibDir = File(app.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibDir, "libproot.so")
    val loader = File(nativeLibDir, "libproot-loader.so")

    /** Written last by the rootfs install: its sha256. */
    val rootfsStamp get() = File(rootfs, "etc/empryo-local-rootfs")
    /** Written last by the engine install: "<version> <sha256>". */
    val engineDir get() = File(rootfs, "opt/empryo")
    val engineStamp get() = File(engineDir, ".installed")
    val empryoHome get() = File(rootfs, "root/.empryo")
    val configJson get() = File(empryoHome, "config.json")

    val engineLog get() = File(logs, "engine.log")

    fun ensureDirs() {
        listOf(downloads, logs, prootTmp).forEach { it.mkdirs() }
        if (NativeFs.exists(rootfs)) listOf("tmp", "root/workspace").forEach { File(rootfs, it).mkdirs() }
    }

    fun rootfsInstalled() = rootfsStamp.isFile
    fun engineInstalled() = engineStamp.isFile
    fun isInstalled() = rootfsInstalled() && engineInstalled()

    companion object {
        const val EMPRYO = "/opt/empryo/empryo"
        const val WORKSPACE = "/root/workspace"
    }
}
