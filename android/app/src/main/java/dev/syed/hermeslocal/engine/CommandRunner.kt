package dev.syed.hermeslocal.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Runs short guest commands (passwd, provider sign-in) in their own proot, beside the server. */
class CommandRunner(context: Context) {
    private val paths = EnginePaths(context)

    data class Result(val exitCode: Int, val output: String)

    /** Runs [argv] in the rootfs, writes [stdin] (if any) and waits up to [timeoutSec]. */
    suspend fun run(argv: List<String>, stdin: String? = null, timeoutSec: Long = 120): Result = withContext(Dispatchers.IO) {
        ProotCommand.preflight(paths)?.let { return@withContext Result(-1, it) }
        val p = ProotCommand.processBuilder(paths, argv).start()
        p.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()); out.flush() } }
        val output = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(output) { output.appendLine(it) } } }
        reader.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.terminate()
            reader.join(2000)
            return@withContext Result(-2, synchronized(output) { output.toString() } + "\n(timed out)")
        }
        reader.join(2000)
        Result(p.exitValue(), synchronized(output) { output.toString() })
    }

    /** An interactive guest process: lines arrive on [onLine]; [send] writes to its stdin. */
    class Session internal constructor(private val process: Process) {
        private val stdin: OutputStream = process.outputStream
        fun send(text: String) = try {
            stdin.write(text.toByteArray()); stdin.flush(); true
        } catch (_: Exception) {
            false
        }
        fun cancel() = process.terminate(1500)
        fun waitFor(): Int = process.waitFor()
    }

    fun startSession(argv: List<String>, onLine: (String) -> Unit): Session {
        ProotCommand.preflight(paths)?.let { throw IllegalStateException(it) }
        val p = ProotCommand.processBuilder(paths, argv).start()
        Thread {
            // Read raw chunks: prompts like "Paste the code:" end without a newline.
            val r = p.inputStream.reader()
            val buf = CharArray(4096)
            val line = StringBuilder()
            try {
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val ch = buf[i]
                        if (ch == '\n' || ch == '\r') {
                            if (line.isNotEmpty()) { onLine(line.toString()); line.setLength(0) }
                        } else line.append(ch)
                    }
                    if (line.isNotEmpty() && !r.ready()) { onLine(line.toString()); line.setLength(0) }
                }
            } catch (_: Exception) {
            }
            if (line.isNotEmpty()) onLine(line.toString())
        }.apply { isDaemon = true }.start()
        return Session(p)
    }
}
