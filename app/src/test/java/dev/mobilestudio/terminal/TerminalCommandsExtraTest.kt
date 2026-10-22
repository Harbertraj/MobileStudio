package dev.mobilestudio.terminal

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TerminalCommandsExtraTest {
    @Test fun splitHandlesQuotesAndEscapes() {
        assertEquals(listOf("git", "commit", "-m", "hello world"), CommandLine.split("git commit -m \"hello world\""))
        assertEquals(listOf("a b", "c"), CommandLine.split("'a b' c"))
        assertEquals(listOf("a b"), CommandLine.split("a\\ b"))
        assertEquals(listOf("say \"hi\""), CommandLine.split("\"say \\\"hi\\\"\""))
        assertEquals(listOf(""), CommandLine.split("''"))
        assertNull(CommandLine.split("git commit -m \"open"))
        assertEquals(emptyList<String>(), CommandLine.split("   "))
    }

    @Test fun operatorsAreOnlyDetectedOutsideQuotes() {
        assertTrue(CommandLine.hasShellOperators("git log | head"))
        assertTrue(CommandLine.hasShellOperators("cd x && ls"))
        assertTrue(CommandLine.hasShellOperators("echo \$(date)"))
        assertFalse(CommandLine.hasShellOperators("git commit -m \"a | b && c; d\""))
        assertFalse(CommandLine.hasShellOperators("git commit -m 'x > y'"))
    }

    @Test fun buildVariantsAndOtherBuiltins() {
        assertEquals(BuiltinCommand.Build, TerminalCommands.parse("build"))
        assertEquals(BuiltinCommand.Build, TerminalCommands.parse("build debug"))
        assertEquals(BuiltinCommand.BuildRelease, TerminalCommands.parse(" build release "))
        assertNull(TerminalCommands.parse("build staging"))
        assertEquals(BuiltinCommand.Install, TerminalCommands.parse("install"))
        assertEquals(BuiltinCommand.Help, TerminalCommands.parse("help"))
        assertNull(TerminalCommands.parse("clear now"))
        assertNull(TerminalCommands.parse("ls -l"))
        assertNull(TerminalCommands.parse("echo \"unterminated"))
    }

    @Test fun gitAndToolchainCarryTheirArguments() {
        assertEquals(BuiltinCommand.Git(listOf("commit", "-m", "two words")), TerminalCommands.parse("git commit -m 'two words'"))
        assertEquals(BuiltinCommand.Git(emptyList()), TerminalCommands.parse("git"))
        assertEquals(BuiltinCommand.ToolchainCmd(listOf("setup")), TerminalCommands.parse("toolchain setup"))
        assertEquals(BuiltinCommand.Keystore(listOf("export")), TerminalCommands.parse("keystore export"))
    }

    @Test fun builtinsInsidePipesAreExplainedNotSilentlyMangled() {
        assertTrue(TerminalCommands.parse("git log | head") is BuiltinCommand.Invalid)
        assertTrue(TerminalCommands.parse("build release && echo done") is BuiltinCommand.Invalid)
        assertNull(TerminalCommands.parse("clear; ls")) // plain shell line
    }

    @Test fun cwdTrackingFollowsPlainCdOnly() {
        val base = File.createTempFile("cwd", "").also { it.delete(); it.mkdirs() }
        try {
            val home = File(base, "home").also { it.mkdirs() }
            val p = File(base, "proj").also { File(it, "sub/deep").mkdirs() }.canonicalFile
            val cwd = ShellCwd(p, home)
            assertTrue(cwd.observe("cd sub")); assertEquals(File(p, "sub"), cwd.current)
            cwd.observe("cd deep"); assertEquals(File(p, "sub/deep"), cwd.current)
            cwd.observe("cd .."); assertEquals(File(p, "sub"), cwd.current)
            cwd.observe("cd -"); assertEquals(File(p, "sub/deep"), cwd.current)
            cwd.observe("cd ~"); assertEquals(home.canonicalFile, cwd.current)
            cwd.observe("cd"); assertEquals(home.canonicalFile, cwd.current)
            cwd.observe("cd /nonexistent/zzz"); assertEquals(home.canonicalFile, cwd.current)
            cwd.observe("cd ${p.path}"); assertEquals(p, cwd.current)
            assertFalse(cwd.observe("ls")); assertFalse(cwd.observe("cd sub && ls")); assertEquals(p, cwd.current)
            cwd.reset(home); assertEquals(home, cwd.current)
        } finally { base.deleteRecursively() }
    }

    @Test fun gitPathsResolveAndRefuseEscapes() {
        val root = File("/r/proj")
        assertEquals("a/b.txt", GitPaths.repoRelative(root, File("/r/proj/a"), "b.txt"))
        assertEquals("b.txt", GitPaths.repoRelative(root, File("/r/proj/a"), "../b.txt"))
        assertEquals(".", GitPaths.repoRelative(root, root, "."))
        assertEquals("x", GitPaths.repoRelative(root, File("/elsewhere"), "/r/proj/x"))
        assertNull(GitPaths.repoRelative(root, root, "../out"))
        assertNull("sibling with the same prefix must not match", GitPaths.repoRelative(root, root, "../proj2/x"))
        assertEquals("../top.txt", GitPaths.display(root, File("/r/proj/sub"), "top.txt"))
    }
}
