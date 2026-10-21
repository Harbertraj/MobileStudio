package dev.mobilestudio.editor

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    private fun kt(s: String) = Diagnostics.check(s, "kt")

    @Test fun balancedIsClean() {
        assertTrue(kt("fun f(a: List<Int>) { val x = arrayOf(1)[0] }").isEmpty())
    }

    @Test fun unclosedBraceReportedAtItsPosition() {
        val d = kt("fun f() {\n  x()\n")
        assertEquals(1, d.size)
        assertEquals(Diagnostic(1, 9, "Unclosed '{'"), d[0])
    }

    @Test fun unmatchedCloser() {
        val d = kt("a)\n")
        assertEquals(listOf(Diagnostic(1, 2, "Unmatched ')'")), d)
    }

    @Test fun mismatchedKinds() {
        val d = kt("(]")
        assertTrue(d.any { it.message == "Unmatched ']'" })
        assertTrue(d.any { it.message == "Unclosed '('" })
    }

    @Test fun bracketsInStringsAndCommentsAreIgnored() {
        assertTrue(kt("val s = \"(\" // {\n/* [ */ val c = ')'").isEmpty())
    }

    @Test fun escapedQuoteInString() {
        assertTrue(kt("val s = \"a\\\"(\"").isEmpty())
    }

    @Test fun unterminatedString() {
        val d = kt("val s = \"abc\nval t = 1")
        assertEquals(listOf(Diagnostic(1, 9, "Unterminated string")), d)
    }

    @Test fun xmlBalanced() {
        val xml = """<?xml version="1.0"?>
            <!-- <unclosed> in comment -->
            <a x="1>2"><b/><c></c></a>"""
        assertTrue(Diagnostics.check(xml, "xml").isEmpty())
    }

    @Test fun xmlUnclosedAndStray() {
        val d = Diagnostics.check("<a>\n<b>\n</a>", "xml")
        assertTrue(d.any { it.message == "Unexpected closing tag </a>" && it.line == 3 })
        assertTrue(d.any { it.message == "Unclosed tag <b>" && it.line == 2 })
    }

    @Test fun unknownExtensionHasNoChecks() {
        assertTrue(Diagnostics.check("((((", "txt").isEmpty())
    }
}
