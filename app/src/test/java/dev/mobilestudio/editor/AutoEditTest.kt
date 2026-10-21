package dev.mobilestudio.editor

import org.junit.Assert.*
import org.junit.Test

class AutoEditTest {
    /** Simulates typing [ch] at [cursor] in [text]. */
    private fun type(text: String, cursor: Int, ch: Char): EditResult? {
        val newText = text.substring(0, cursor) + ch + text.substring(cursor)
        return AutoEdit.apply(text, cursor, newText, cursor + 1)
    }

    @Test fun enterKeepsIndent() {
        val r = type("    foo()", 9, '\n')!!
        assertEquals("    foo()\n    ", r.text)
        assertEquals(r.text.length, r.cursor)
    }

    @Test fun enterAfterOpenBraceIndentsMore() {
        val r = type("fun f() {", 9, '\n')!!
        assertEquals("fun f() {\n    ", r.text)
        assertEquals(r.text.length, r.cursor)
    }

    @Test fun enterBetweenBracesSplitsBlock() {
        val r = type("fun f() {}", 9, '\n')!!
        assertEquals("fun f() {\n    \n}", r.text)
        assertEquals("fun f() {\n    ".length, r.cursor)
    }

    @Test fun enterOnPlainLineNeedsNoChange() {
        val r = type("foo", 3, '\n')!!
        assertEquals("foo\n", r.text)
        assertEquals(4, r.cursor)
    }

    @Test fun openParenAutoCloses() {
        val r = type("foo", 3, '(')!!
        assertEquals("foo()", r.text)
        assertEquals(4, r.cursor)
    }

    @Test fun noAutoCloseBeforeWord() {
        assertNull(type("foo bar", 4, '('))
    }

    @Test fun typingCloserSkipsOverExisting() {
        val r = type("foo()", 4, ')')!!
        assertEquals("foo()", r.text)
        assertEquals(5, r.cursor)
    }

    @Test fun typingCloserWithoutExistingIsKept() {
        assertNull(type("foo", 3, ')'))
    }

    @Test fun quoteAfterLetterIsNotPaired() {
        assertNull(type("don", 3, '"'))
    }

    @Test fun quoteAutoCloses() {
        val r = type("val s = ", 8, '"')!!
        assertEquals("val s = \"\"", r.text)
        assertEquals(9, r.cursor)
    }

    @Test fun pasteIsIgnored() {
        assertNull(AutoEdit.apply("a", 1, "abc", 3))
    }

    @Test fun plainCharIsIgnored() {
        assertNull(type("ab", 2, 'c'))
    }
}
