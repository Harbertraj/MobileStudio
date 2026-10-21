package dev.mobilestudio.build

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

interface ProcessRunner {
    /** Runs [command], streaming merged stdout/stderr lines to [onLine]. Returns the exit code, or -1 if cancelled. */
    fun run(command: List<String>, workDir: File?, onLine: (String) -> Unit, isCancelled: () -> Boolean): Int
}

class JvmProcessRunner : ProcessRunner {
    override fun run(command: List<String>, workDir: File?, onLine: (String) -> Unit, isCancelled: () -> Boolean): Int {
        val process = ProcessBuilder(command).directory(workDir).redirectErrorStream(true).start()
        val reader = thread(isDaemon = true) {
            process.inputStream.bufferedReader().forEachLine(onLine)
        }
        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
            if (isCancelled()) {
                process.destroyForcibly()
                process.waitFor()
                reader.join(1000)
                return -1
            }
        }
        reader.join(1000)
        return process.exitValue()
    }
}
