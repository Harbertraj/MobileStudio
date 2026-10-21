package dev.mobilestudio.terminal

import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * A long-lived shell process. Commands are written to its stdin; stdout and stderr are merged and
 * delivered in chunks to [onOutput] (from a background thread). There is no pseudo-terminal, so
 * full-screen programs and Ctrl+C are not supported: [stop] ends the shell, and a new session starts clean.
 */
class ShellSession(
    private val command: List<String>,
    private val workDir: File,
    private val environment: Map<String, String> = emptyMap(),
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private var process: Process? = null
    private var stdin: OutputStream? = null
    @Volatile private var stopping = false

    val isRunning: Boolean get() = process?.isAlive == true

    @Synchronized
    fun start(): Result<Unit> = runCatching {
        check(!isRunning) { "Shell is already running" }
        stopping = false
        val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        builder.environment().putAll(environment)
        val p = builder.start()
        process = p
        stdin = p.outputStream
        thread(isDaemon = true, name = "shell-reader") {
            val reader = InputStreamReader(p.inputStream, Charsets.UTF_8)
            val buf = CharArray(4096)
            while (true) {
                val n = try { reader.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                onOutput(String(buf, 0, n))
            }
            val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
            if (!stopping) onExit(code)
        }
    }

    /** Sends one line to the shell. Returns false when the shell is not running or the pipe is closed. */
    @Synchronized
    fun send(line: String): Boolean {
        if (!isRunning) return false
        return try {
            stdin?.write((line + "\n").toByteArray(Charsets.UTF_8))
            stdin?.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun stop() {
        stopping = true
        val p = process ?: return
        p.destroy()
        if (!p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
        process = null
        stdin = null
    }
}
