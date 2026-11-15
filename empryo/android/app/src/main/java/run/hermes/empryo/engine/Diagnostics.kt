package run.hermes.empryo.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import run.hermes.empryo.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thermal status (API 29) + headroom (API 30, at most once per 10 s or it returns NaN). */
object Thermal {
    data class Reading(val status: Int, val headroom: Float) {
        val label get() = when (status) {
            0 -> "Cool"; 1 -> "Light"; 2 -> "Moderate"; 3 -> "Severe"; 4 -> "Critical"; 5 -> "Emergency"; 6 -> "Shutdown"; else -> "Unknown"
        }
    }

    fun read(context: Context): Reading {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val headroom = if (Build.VERSION.SDK_INT >= 30) runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN) else Float.NaN
        return Reading(pm.currentThermalStatus, headroom)
    }
}

/**
 * Diagnostics for the open PoC question (phone-service died with ENOENT on background.json.lock under the
 * app's proot, x86_64 emulator only): versions, doctor, a bridge start, idle cost of the engine trees,
 * thermal state and disk. Shown live, copyable, and written to filesDir/logs/diagnostics-*.txt.
 */
object Diagnostics {
    private val _report = MutableStateFlow("")
    val report: StateFlow<String> = _report.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    @Volatile var lastFile: File? = null

    private fun out(s: String) { _report.value += s + "\n" }

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        if (_running.value) return@withContext
        _running.value = true
        _report.value = ""
        val paths = Paths(context)
        val runner = Runner(context)
        val s = Settings.get(context).value
        try {
            out("# Empryo Local diagnostics ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            out("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) abi=${BuildConfig.ENGINE_ABI} empryo=${BuildConfig.EMPRYO_VERSION}")
            out("device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) android ${Build.VERSION.RELEASE} sdk ${Build.VERSION.SDK_INT} kernel ${System.getProperty("os.version")}")
            out("engine phase=${EngineState.status.value.phase} acpPid=${EngineService.acpPid} bridgePid=${EngineService.bridgePid}")
            Proot.preflight(paths)?.let { out("NOT READY: $it"); return@withContext }
            runCatching { EngineConfig.apply(paths, s) }

            section("empryo --version")
            runner.run(listOf(Paths.EMPRYO, "--version"), 60).let { out("rc=${it.exitCode} ${it.ms} ms\n${it.output.trim()}") }

            section("empryo doctor")
            runner.run(listOf(Paths.EMPRYO, "doctor"), 180).let { out("rc=${it.exitCode} ${it.ms} ms\n${it.output.trim()}") }

            section("network inside the engine (DNS + HTTPS from Bun, like sign-in and chat)")
            out("Android DNS servers (active network): ${GuestSetup.networkDns(context).ifEmpty { listOf("none") }.joinToString(" ")}")
            File(paths.rootfs, NETCHECK.removePrefix("/")).apply { parentFile?.mkdirs(); writeText(NETCHECK_JS) }
            runner.run(listOf("/bin/sh", "-c", "grep -v '^#' /etc/resolv.conf; getent ahostsv4 api.anthropic.com | head -1 || echo 'getent: no answer'; /usr/bin/env BUN_BE_BUN=1 ${Paths.EMPRYO} $NETCHECK"), 120)
                .let { out("rc=${it.exitCode} ${it.ms} ms\n${it.output.trim()}") }

            section("mkdir probe (PoC blocker: background.json.lock ENOENT)")
            runner.run(listOf("/bin/sh", "-c", "mkdir -p /root/.empryo/mobile && mkdir /root/.empryo/mobile/diag.lock && echo MKDIR_OK; rmdir /root/.empryo/mobile/diag.lock; ls -la /root/.empryo/mobile"), 60)
                .let { out("rc=${it.exitCode}\n${it.output.trim()}") }

            section("bridge: empryo phone-service")
            val bpid = EngineService.bridgePid
            if (bpid != null) {
                out("the pairing bridge is already running (pid $bpid): ${EngineState.status.value.bridge}")
                out("port ${s.bridgePort}..${s.bridgePort + 3} open: ${(s.bridgePort..s.bridgePort + 3).filter { EngineService.portOpen(it) }}")
                out(sample("bridge tree (pairing)", bpid, 10))
            } else bridgeTest(paths, s)
            File(paths.empryoHome, "logs/errors.jsonl").takeIf { it.isFile }?.let { out("errors.jsonl (tail):\n" + it.readText().takeLast(4000).trim()) }
            File(paths.empryoHome, "mobile/service.log").takeIf { it.isFile }?.let { out("mobile/service.log (tail):\n" + it.readText().takeLast(3000).trim()) }

            section("engine idle cost (empryo acp tree)")
            val apid = EngineService.acpPid
            if (apid != null) out(sample("acp tree", apid, 15)) else out("engine is off: start it with the lever and run again for idle numbers")

            section("thermal")
            Thermal.read(context).let { out("status=${it.status} (${it.label}) headroom(10s)=${it.headroom}") }

            section("disk")
            val fs = StatFs(context.filesDir.absolutePath)
            out("rootfs total ${mb(du(paths.rootfs))} MB · /opt/empryo ${mb(du(paths.engineDir))} MB · /root/.empryo ${mb(du(paths.empryoHome))} MB")
            out("free ${fs.availableBytes shr 20} MB of ${fs.totalBytes shr 20} MB")
        } catch (e: Throwable) {
            out("diagnostics stopped: $e")
        } finally {
            val f = File(paths.logs, "diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
            runCatching { paths.logs.mkdirs(); f.writeText(_report.value); lastFile = f; out("saved ${f.absolutePath}") }
            _running.value = false
        }
    }

    /** One bridge start with proot's link2symlink, and if that dies on its socket hard link, one without. */
    private suspend fun bridgeTest(paths: Paths, s: SettingsSnapshot) {
        val first = bridgeOnce(paths, s, link2symlink = true)
        if (!first && File(paths.empryoHome, "mobile").exists()) {
            out("\n-- again without link2symlink (real hard links for the control socket)")
            bridgeOnce(paths, s, link2symlink = false)
        }
    }

    private suspend fun bridgeOnce(paths: Paths, s: SettingsSnapshot, link2symlink: Boolean): Boolean {
        val via = if (s.lanAccess) "wifi" else "local"
        out("argv: phone-service --cwd ${Paths.WORKSPACE} --login-item --via $via (remotePort ${s.bridgePort}, link2symlink=$link2symlink)")
        val lines = StringBuilder()
        val p = Proot.processBuilder(paths, listOf(Paths.EMPRYO, "phone-service", "--cwd", Paths.WORKSPACE, "--login-item", "--via", via), link2symlink = link2symlink).start()
        p.outputStream.close()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(lines) { lines.appendLine(it) } } } }.apply { isDaemon = true; start() }
        val t0 = System.currentTimeMillis()
        var openPort: Int? = null
        while (System.currentTimeMillis() - t0 < 25_000) {
            openPort = (s.bridgePort..s.bridgePort + 3).firstOrNull { EngineService.portOpen(it) }
            if (openPort != null || !p.isAliveCompat()) break
            delay(250)
        }
        val dt = System.currentTimeMillis() - t0
        if (openPort != null) {
            out("BRIDGE OK: port $openPort open after $dt ms")
            p.pidCompat()?.let { out(sample("phone-service tree", it, 10)) }
        } else if (!p.isAliveCompat()) {
            out("BRIDGE EXITED after $dt ms, rc=${runCatching { p.exitValue() }.getOrDefault(-1)}")
        } else out("BRIDGE NOT LISTENING after $dt ms (process still alive)")
        p.terminate(4000)
        reader.join(2000)
        out("phone-service output:\n" + synchronized(lines) { lines.toString() }.trim().ifEmpty { "(none)" })
        return openPort != null
    }

    private const val NETCHECK = "/opt/empryo-local/netcheck.js"
    private val NETCHECK_JS = """
        for (const u of ["https://api.anthropic.com/", "https://chatgpt.com/", "https://dl.empryo.com/latest.txt"]) {
          const t = Date.now(), h = new URL(u).host;
          try { const r = await fetch(u, { signal: AbortSignal.timeout(25000) }); console.log(`NET_OK ${'$'}{h} http ${'$'}{r.status} ${'$'}{Date.now() - t} ms`); }
          catch (e) { console.log(`NET_FAIL ${'$'}{h} ${'$'}{(e && e.code) || ""} ${'$'}{String((e && e.message) || e).slice(0, 160)} ${'$'}{Date.now() - t} ms`); }
        }
    """.trimIndent()

    private fun section(title: String) = out("\n## $title")

    // ---- process tree sampling (the app may read /proc of its own uid's processes)

    private class Snap(val ticks: Long, val ctx: Long, val rssKb: Long, val names: List<String>)

    private fun children(): Map<Int, List<Int>> {
        val map = HashMap<Int, MutableList<Int>>()
        File("/proc").list()?.mapNotNull { it.toIntOrNull() }?.forEach { pid ->
            val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return@forEach
            val ppid = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull() ?: return@forEach
            map.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        return map
    }

    private fun tree(root: Int): List<Int> {
        val kids = children()
        val out = mutableListOf(root)
        var i = 0
        while (i < out.size) { kids[out[i]]?.let { out += it }; i++ }
        return out
    }

    private fun snap(root: Int): Snap {
        var ticks = 0L; var ctx = 0L; var rss = 0L
        val names = mutableListOf<String>()
        for (pid in tree(root)) {
            val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: continue
            names += stat.substringAfter('(').substringBeforeLast(')')
            val f = stat.substringAfterLast(')').trim().split(' ')
            ticks += (f.getOrNull(11)?.toLongOrNull() ?: 0L) + (f.getOrNull(12)?.toLongOrNull() ?: 0L)
            runCatching { File("/proc/$pid/status").readLines() }.getOrNull()?.firstOrNull { it.startsWith("VmRSS:") }
                ?.let { rss += it.filter(Char::isDigit).toLongOrNull() ?: 0L }
            File("/proc/$pid/task").list()?.forEach { tid ->
                runCatching { File("/proc/$pid/task/$tid/status").readLines() }.getOrNull()
                    ?.filter { it.startsWith("voluntary_ctxt_switches") || it.startsWith("nonvoluntary_ctxt_switches") }
                    ?.forEach { ctx += it.filter(Char::isDigit).toLongOrNull() ?: 0L }
            }
        }
        return Snap(ticks, ctx, rss, names)
    }

    private suspend fun sample(label: String, root: Int, seconds: Int): String {
        val hz = Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1L)
        val a = snap(root)
        val t0 = System.nanoTime()
        delay(seconds * 1000L)
        val b = snap(root)
        val dt = (System.nanoTime() - t0) / 1e9
        val cpu = (b.ticks - a.ticks).toDouble() / hz / dt * 100
        val ctx = (b.ctx - a.ctx) / dt
        return "%s over %.1f s: %d procs [%s] · RSS %.1f MB · CPU %.2f%% of one core · %.1f context switches/s"
            .format(Locale.US, label, dt, b.names.size, b.names.joinToString(", "), b.rssKb / 1024.0, cpu, ctx)
    }

    private fun mb(bytes: Long) = bytes shr 20

    /** Bytes under [root] without following symlinks (a rootfs's absolute links point at the host). */
    private fun du(root: File): Long {
        val st = runCatching { Os.lstat(root.path) }.getOrNull() ?: return 0
        if (OsConstants.S_ISLNK(st.st_mode)) return 0
        if (!OsConstants.S_ISDIR(st.st_mode)) return st.st_blocks * 512
        return st.st_blocks * 512 + (root.list()?.sumOf { du(File(root, it)) } ?: 0L)
    }
}
