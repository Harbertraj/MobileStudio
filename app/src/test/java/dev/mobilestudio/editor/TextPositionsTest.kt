package dev.mobilestudio.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPositionsTest {
    private val text = "ab\ncde\n\nf"

    @Test fun offsetOfLinesAndColumns() {
        assertEquals(0, TextPositions.offsetOf(text, 1, 1))
        assertEquals(1, TextPositions.offsetOf(text, 1, 2))
        assertEquals(3, TextPositions.offsetOf(text, 2, 1))
        assertEquals(5, TextPositions.offsetOf(text, 2, 3))
        assertEquals(7, TextPositions.offsetOf(text, 3, 1)) // empty line
        assertEquals(8, TextPositions.offsetOf(text, 4, 1))
    }

    @Test fun columnPastEndOfLineIsClamped() {
        assertEquals(2, TextPositions.offsetOf(text, 1, 99))
        assertEquals(9, TextPositions.offsetOf(text, 4, 99))
    }

    @Test fun lineBeyondEndGoesToEnd() {
        assertEquals(text.length, TextPositions.offsetOf(text, 50, 1))
    }

    @Test fun lineIndexOfOffsets() {
        assertEquals(0, TextPositions.lineIndexOf(text, 0))
        assertEquals(0, TextPositions.lineIndexOf(text, 2))
        assertEquals(1, TextPositions.lineIndexOf(text, 3))
        assertEquals(3, TextPositions.lineIndexOf(text, 99))
    }
}
