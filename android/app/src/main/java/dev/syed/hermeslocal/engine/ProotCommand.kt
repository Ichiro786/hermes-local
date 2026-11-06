package dev.syed.hermeslocal.engine

import java.io.File

/**
 * The proot command line (same shape as OpenBot Local's, which runs on Syed's phone):
 *
 *   libproot.so --kill-on-exit --link2symlink --sysvipc -0 -L -r $R -w /root -b /dev -b /proc -b /sys
 *     -b /dev/urandom:/dev/random -b /proc/self/fd:/dev/fd ... -b $R/tmp:/dev/shm [fake /proc files]
 *     /usr/bin/env -i HOME=/root ... <guest argv>
 *
 * proot lives in nativeLibraryDir because since targetSdk 29 an app may only exec files there (W^X);
 * PROOT_LOADER routes every guest execve through the loader, so guest binaries never need exec rights.
 */
object ProotCommand {
    const val GUEST_PATH = "/opt/hermes-local/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    private val ALWAYS_FAKE = listOf(
        "max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
        "cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
    )

    /** /proc files apps cannot read on Android; bound from the rootfs when unreadable. */
    private val FAKE_IF_UNREADABLE = listOf("loadavg", "stat", "uptime", "version", "vmstat")

    fun guestEnv(extra: Map<String, String> = emptyMap()): Map<String, String> = linkedMapOf(
        "HOME" to "/root",
        "USER" to "root",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        "PATH" to GUEST_PATH,
        "HERMES_HOME" to "/root/.hermes",
        "HERMES_RUNTIME_DIR" to "/opt/hermes/tools",
        // The engine is replaced as a whole; agy never updates itself (Hermes: install stamp, see build-rootfs.sh).
        "AGY_CLI_DISABLE_AUTO_UPDATE" to "1",
    ).apply { putAll(extra) }

    fun argv(paths: EnginePaths, guestArgv: List<String>, env: Map<String, String>): List<String> {
        val r = paths.rootfs.absolutePath
        val args = mutableListOf(
            paths.proot.absolutePath,
            "--kill-on-exit", "--link2symlink", "--sysvipc", "-0", "-L",
            "-r", r, "-w", "/root",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "$r/tmp:/dev/shm",
        )
        val fakes = File(paths.rootfs, "opt/hermes-local/proc")
        for ((name, guest) in ALWAYS_FAKE) {
            val fake = File(fakes, name)
            if (fake.isFile) args += listOf("-b", "${fake.absolutePath}:$guest")
        }
        for (name in FAKE_IF_UNREADABLE) {
            val fake = File(fakes, name)
            if (fake.isFile && !hostProcReadable(name)) args += listOf("-b", "${fake.absolutePath}:/proc/$name")
        }
        args += listOf("/usr/bin/env", "-i")
        env.forEach { (k, v) -> args += "$k=$v" }
        args += guestArgv
        return args
    }

    private fun hostProcReadable(name: String): Boolean = try {
        File("/proc/$name").inputStream().use { it.read() >= 0 }
    } catch (_: Exception) {
        false
    }

    fun processBuilder(paths: EnginePaths, guestArgv: List<String>, extraEnv: Map<String, String> = emptyMap()): ProcessBuilder {
        paths.ensureDirs()
        val pb = ProcessBuilder(argv(paths, guestArgv, guestEnv(extraEnv)))
            .directory(paths.rootfs)
            .redirectErrorStream(true)
        val env = pb.environment()
        val inherited = HashMap(env)
        env.clear()
        listOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_RUNTIME_ROOT", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT")
            .forEach { k -> inherited[k]?.let { env[k] = it } }
        env["PATH"] = "/system/bin:/system/xbin"
        env["PROOT_TMP_DIR"] = paths.prootTmp.absolutePath
        env["PROOT_LOADER"] = paths.loader.absolutePath
        // link2symlink keeps its backing files in one directory, so renaming a directory that holds
        // emulated hard links (uv moves unpacked wheels into its cache) does not break them.
        // Without it plugin/extra installs fail with "failed to hardlink file ... .l2s.*".
        env["PROOT_L2S_DIR"] = File(paths.rootfs, ".l2s").apply { mkdirs() }.absolutePath
        env["LD_LIBRARY_PATH"] = paths.nativeLibDir.absolutePath
        return pb
    }

    /** Why proot cannot start, or null. */
    fun preflight(paths: EnginePaths): String? = when {
        !paths.proot.exists() -> "libproot.so missing from ${paths.nativeLibDir}"
        !paths.loader.exists() -> "libproot-loader.so missing"
        !File(paths.nativeLibDir, "libtalloc.so").exists() -> "libtalloc.so missing"
        !paths.isInstalled() -> "Engine not installed"
        else -> null
    }
}
