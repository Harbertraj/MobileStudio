package dev.mobilestudio.editor

import org.junit.Assert.*
import org.junit.Test

class UndoStackTest {
    @Test fun burstOfTypingIsOneStep() {
        val s = UndoStack("")
        s.record("a", 0); s.record("ab", 100); s.record("abc", 200)
        assertEquals("", s.undo())
        assertFalse(s.canUndo)
    }

    @Test fun pauseStartsNewStep() {
        val s = UndoStack("")
        s.record("a", 0); s.record("ab", 5000)
        assertEquals("a", s.undo())
        assertEquals("", s.undo())
        assertNull(s.undo())
    }

    @Test fun redoRestoresAndNewEditClearsRedo() {
        val s = UndoStack("x")
        s.record("xy", 0)
        assertEquals("x", s.undo())
        assertTrue(s.canRedo)
        assertEquals("xy", s.redo())
        s.undo()
        s.record("xz", 10_000)
        assertFalse(s.canRedo)
        assertNull(s.redo())
    }

    @Test fun editAfterUndoIsNotMergedIntoPreviousBurst() {
        val s = UndoStack("")
        s.record("a", 0)
        s.undo()
        s.record("b", 10) // within the merge window of the old edit, but must be its own step
        assertEquals("", s.undo())
    }

    @Test fun unchangedTextIsIgnored() {
        val s = UndoStack("same")
        s.record("same", 0)
        assertFalse(s.canUndo)
    }

    @Test fun historyIsBounded() {
        val s = UndoStack("", maxEntries = 3, mergeWindowMs = 0)
        for (i in 1..10) s.record("v$i", i * 1000L)
        var n = 0
        while (s.undo() != null) n++
        assertEquals(3, n)
    }
}
