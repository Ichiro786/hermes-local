package run.hermes.empryo.engine

import java.io.File

/**
 * The proot command line, the same shape Hermes Local runs on Syed's phone:
 *
 *   libproot.so --kill-on-exit --link2symlink --sysvipc -0 -L -r $R -w /root -b /dev -b /proc -b /sys ...
 *     [fake /proc files] /usr/bin/env -i HOME=/root ... <guest argv>
 *
 * proot lives in nativeLibraryDir (W^X: the only exec-able app path); PROOT_LOADER routes every guest
 * execve through the loader. Seccomp stays on (never PROOT_NO_SECCOMP).
 */
object Proot {
    const val GUEST_PATH = "/opt/empryo:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    private val ALWAYS_FAKE = listOf(
        "max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
        "cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
    )

    /** /proc files apps cannot read on Android (Empryo exits on an unreadable /proc/version). */
    private val FAKE_IF_UNREADABLE = listOf("loadavg", "stat", "uptime", "version", "vmstat")

    /** Engine env for phones: no telemetry, small worker pools, bundle deps instead of a runtime copy. */
    fun guestEnv(extra: Map<String, String> = emptyMap()): Map<String, String> = linkedMapOf(
        "HOME" to "/root",
        "USER" to "root",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        "PATH" to GUEST_PATH,
        "TERM" to "dumb",
        "NO_COLOR" to "1",
        "EMPRYO_NO_TTY" to "1",
        "EMPRYO_TELEMETRY" to "0",
        "DO_NOT_TRACK" to "1",
        "EMPRYO_RUNTIME_DIR" to "/opt/empryo/deps",
        "EMPRYO_WASM_DIR" to "/opt/empryo/deps/wasm",
        "EMPRYO_HEAVY_WORK_SLOTS" to "1",
        "EMPRYO_GENOME_SCAN_SLOTS" to "1",
        "EMPRYO_LSP_MAX_CLIENTS" to "2",
        "EMPRYO_LSP_IDLE_MS" to "60000",
        // If the app process dies, the engine tree dies with it (PR_SET_PDEATHSIG).
        "BUN_FEATURE_FLAG_NO_ORPHANS" to "1",
    ).apply { putAll(extra) }

    /** [link2symlink] false: real hard links (phone-service links its control socket; see EngineService.runBridge). */
    fun argv(paths: Paths, guestArgv: List<String>, env: Map<String, String>, link2symlink: Boolean = true): List<String> {
        val r = paths.rootfs.absolutePath
        val args = mutableListOf(paths.proot.absolutePath, "--kill-on-exit")
        if (link2symlink) args += "--link2symlink"
        args += listOf(
            "--sysvipc", "-0", "-L",
            "-r", r, "-w", "/root",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "$r/tmp:/dev/shm",
        )
        val fakes = File(paths.rootfs, "opt/empryo-local/proc")
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

    /** stderr is merged into stdout unless [splitStderr] (ACP: stdout is the JSON-RPC channel). */
    fun processBuilder(
        paths: Paths, guestArgv: List<String>, splitStderr: Boolean = false, extraEnv: Map<String, String> = emptyMap(), link2symlink: Boolean = true,
    ): ProcessBuilder {
        paths.ensureDirs()
        GuestSetup.apply(paths)
        val pb = ProcessBuilder(argv(paths, guestArgv, guestEnv(extraEnv), link2symlink))
            .directory(paths.rootfs)
            .redirectErrorStream(!splitStderr)
        val env = pb.environment()
        val inherited = HashMap(env)
        env.clear()
        listOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_RUNTIME_ROOT", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT")
            .forEach { k -> inherited[k]?.let { env[k] = it } }
        env["PATH"] = "/system/bin:/system/xbin"
        env["PROOT_TMP_DIR"] = paths.prootTmp.absolutePath
        env["PROOT_LOADER"] = paths.loader.absolutePath
        // One link2symlink store, else renames of emulated hard links fail ("failed to hardlink .l2s.*").
        env["PROOT_L2S_DIR"] = File(paths.rootfs, ".l2s").apply { mkdirs() }.absolutePath
        env["LD_LIBRARY_PATH"] = paths.nativeLibDir.absolutePath
        return pb
    }

    /** Why proot cannot start, or null. */
    fun preflight(paths: Paths): String? = when {
        !paths.proot.exists() -> "libproot.so missing from ${paths.nativeLibDir}"
        !paths.loader.exists() -> "libproot-loader.so missing"
        !File(paths.nativeLibDir, "libtalloc.so").exists() -> "libtalloc.so missing"
        !paths.isInstalled() -> "Engine not installed"
        else -> null
    }
}
