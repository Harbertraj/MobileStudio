package dev.mobilestudio.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalBufferTest {
    private fun text(vararg chunks: String) = TerminalBuffer().also { b -> chunks.forEach(b::append) }.text

    @Test fun plainTextPassesThrough() = assertEquals("hello\nworld\n", text("hello\n", "world\n"))

    @Test fun ansiColourCodesAreRemoved() = assertEquals("red ok", text("\u001B[31mred\u001B[0m ok"))

    @Test fun escapeSplitAcrossChunksIsStillRemoved() {
        assertEquals("ab", text("a\u001B", "[3", "1mb"))
        assertEquals("ab", text("a\u001B[", "0m", "b"))
    }

    @Test fun oscTitleSequenceIsRemoved() {
        assertEquals("x", text("\u001B]0;my title\u0007x"))
        assertEquals("x", text("\u001B]0;my title\u001B\\x"))
    }

    @Test fun crlfIsOneNewline() = assertEquals("a\nb\n", text("a\r\nb\r\n"))

    @Test fun crlfSplitAcrossChunksKeepsTheLine() = assertEquals("abc\ndef", text("abc\r", "\ndef"))

    @Test fun loneCarriageReturnRedrawsTheLine() {
        assertEquals("done\n", text("10%\r50%\rdone\n"))
        assertEquals("line1\nnew", text("line1\nold\rnew"))
    }

    @Test fun backspaceDeletesWithinTheLineOnly() {
        assertEquals("ab", text("abc\b"))
        assertEquals("a\n", text("a\n\b"))
    }

    @Test fun scrollbackIsBoundedAndCutsAtALineBoundary() {
        val b = TerminalBuffer(maxChars = 50)
        repeat(100) { b.append("line number $it\n") }
        assertTrue(b.text.length <= 50)
        assertTrue(b.text.endsWith("line number 99\n"))
        assertTrue(b.text.startsWith("line number "))
    }

    @Test fun concurrentWritersAndClearsNeverCorruptTheBuffer() {
        val b = TerminalBuffer(maxChars = 2_000)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (1..8).map { id ->
            Thread {
                try {
                    repeat(20_000) { i ->
                        b.append("t$id line $i\r\n\u001B[31mred\u001B[0m\rover\n")
                        if (i % 97 == 0) b.clear()
                        b.text.length
                    }
                } catch (e: Throwable) { errors += e }
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertTrue("unexpected failures: $errors", errors.isEmpty())
        assertTrue(b.text.length <= 2_000)
    }

    @Test fun clearResetsEverything() {
        val b = TerminalBuffer(); b.append("x\u001B["); b.clear(); b.append("y")
        assertEquals("y", b.text)
    }
}

class CommandHistoryTest {
    @Test fun navigatesOldestToNewestAndBack() {
        val h = CommandHistory()
        h.add("one"); h.add("two"); h.add("three")
        assertEquals("three", h.previous())
        assertEquals("two", h.previous())
        assertEquals("one", h.previous())
        assertEquals("one", h.previous()) // stays on the oldest
        assertEquals("two", h.next())
        assertEquals("three", h.next())
        assertNull(h.next()) // past the newest: clear the input
    }

    @Test fun blankAndImmediateDuplicatesAreIgnored() {
        val h = CommandHistory()
        h.add(" "); h.add("ls"); h.add("ls"); h.add("pwd")
        assertEquals("pwd", h.previous()); assertEquals("ls", h.previous()); assertEquals("ls", h.previous())
    }

    @Test fun emptyHistory() { assertNull(CommandHistory().previous()); assertNull(CommandHistory().next()) }

    @Test fun adding_resets_browsing_position() {
        val h = CommandHistory(); h.add("a"); h.add("b"); h.previous(); h.previous(); h.add("c")
        assertEquals("c", h.previous())
    }

    @Test fun historyIsBounded() {
        val h = CommandHistory(max = 3)
        (1..10).forEach { h.add("c$it") }
        val seen = generateSequence { h.previous() }.take(10).toList()
        assertEquals(setOf("c10", "c9", "c8"), seen.toSet())
    }
}

class TerminalCommandsTest {
    @Test fun parsesBuiltins() {
        assertEquals(BuiltinCommand.Clear, TerminalCommands.parse("clear"))
        assertEquals(BuiltinCommand.Build, TerminalCommands.parse("  build "))
        assertNull(TerminalCommands.parse("build now"))
        assertNull(TerminalCommands.parse("ls"))
    }
}
