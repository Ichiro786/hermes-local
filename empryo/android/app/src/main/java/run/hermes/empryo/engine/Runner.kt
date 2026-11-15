package run.hermes.empryo.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Short guest commands (version, doctor, sign-in, set-key) in their own proot, beside the engine. */
class Runner(context: Context) {
    private val paths = Paths(context)

    data class Result(val exitCode: Int, val output: String, val ms: Long)

    suspend fun run(argv: List<String>, timeoutSec: Long = 120): Result = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        Proot.preflight(paths)?.let { return@withContext Result(-1, it, 0) }
        val p = Proot.processBuilder(paths, argv).start()
        p.outputStream.close()
        val output = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(output) { output.appendLine(it) } } }
        reader.start()
        val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) p.terminate()
        reader.join(2000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        Result(if (done) p.exitValue() else -2, synchronized(output) { output.toString() } + if (done) "" else "\n(timed out after ${timeoutSec}s)", ms)
    }

    /** An interactive guest process: output arrives on [onLine] (prompts without a newline too); [send] writes stdin. */
    class Session internal constructor(val process: Process) {
        private val stdin: OutputStream = process.outputStream
        fun send(text: String) = try {
            stdin.write(text.toByteArray()); stdin.flush(); true
        } catch (_: Exception) {
            false
        }
        fun cancel() = process.terminate(1500)
    }

    fun startSession(argv: List<String>, onLine: (String) -> Unit, onExit: (Int) -> Unit): Session {
        Proot.preflight(paths)?.let { throw IllegalStateException(it) }
        val p = Proot.processBuilder(paths, argv).start()
        Thread {
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
            onExit(runCatching { p.waitFor() }.getOrDefault(-1))
        }.apply { isDaemon = true }.start()
        return Session(p)
    }
}
