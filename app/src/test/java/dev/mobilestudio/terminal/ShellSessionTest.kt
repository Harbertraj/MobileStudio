package dev.mobilestudio.terminal

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShellSessionTest {
    private lateinit var dir: File
    private var session: ShellSession? = null
    private val out = StringBuffer()

    @Before fun setUp() { dir = File.createTempFile("shelltest", "").also { it.delete(); it.mkdirs() } }
    @After fun tearDown() { session?.stop(); dir.deleteRecursively() }

    private fun start(env: Map<String, String> = emptyMap(), onExit: (Int) -> Unit = {}): ShellSession =
        ShellSession(listOf("sh"), dir, env, { out.append(it) }, onExit).also { it.start().getOrThrow(); session = it }

    private fun waitFor(text: String, timeoutMs: Long = 5000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (out.contains(text)) return true; Thread.sleep(20) }
        return false
    }

    @Test fun runsCommandsAndStreamsOutput() {
        val s = start()
        assertTrue(s.send("echo hello-from-shell"))
        assertTrue(out.toString(), waitFor("hello-from-shell"))
    }

    @Test fun startsInTheWorkingDirectory() {
        val s = start()
        s.send("pwd")
        assertTrue(out.toString(), waitFor(dir.canonicalFile.name))
    }

    @Test fun stateIsKeptBetweenCommands() {
        val s = start()
        File(dir, "sub").mkdirs()
        s.send("cd sub"); s.send("pwd"); s.send("X=42"); s.send("echo value-\$X")
        assertTrue(out.toString(), waitFor("value-42"))
        assertTrue(out.toString().contains("/sub"))
    }

    @Test fun stderrIsMerged() {
        val s = start()
        s.send("echo oops 1>&2")
        assertTrue(waitFor("oops"))
    }

    @Test fun environmentIsPassed() {
        val s = start(env = mapOf("MS_TEST_VAR" to "xyz"))
        s.send("echo \$MS_TEST_VAR")
        assertTrue(out.toString(), waitFor("xyz"))
    }

    @Test fun exitCodeIsReported() {
        val latch = CountDownLatch(1); var code = -99
        val s = start { code = it; latch.countDown() }
        s.send("exit 7")
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(7, code)
        assertFalse(s.isRunning)
        assertFalse(s.send("echo late"))
    }

    @Test fun stopKillsALongRunningCommandWithoutReportingExit() {
        var exited = false
        val s = start { exited = true }
        s.send("sleep 60")
        Thread.sleep(200)
        val t0 = System.currentTimeMillis()
        s.stop()
        assertTrue(System.currentTimeMillis() - t0 < 5000)
        assertFalse(s.isRunning)
        Thread.sleep(200)
        assertFalse("stop() must not look like an unexpected exit", exited)
    }

    @Test fun cannotStartTwice() {
        val s = start()
        assertTrue(s.start().isFailure)
    }

    @Test fun badWorkingDirectoryFailsToStart() {
        val s = ShellSession(listOf("sh"), File(dir, "nope"), emptyMap(), { }, { })
        assertTrue(s.start().isFailure)
    }
}
