package dev.mobilestudio.editor

import org.junit.Assert.*
import org.junit.Test

class FindReplaceTest {
    private fun matches(text: String, o: FindOptions) = FindReplace.findAll(text, o).getOrThrow()

    @Test fun caseInsensitiveByDefault() {
        assertEquals(2, matches("Foo foo", FindOptions("foo")).size)
        assertEquals(1, matches("Foo foo", FindOptions("foo", caseSensitive = true)).size)
    }

    @Test fun plainQueryIsLiteral() {
        assertEquals(1, matches("a.c abc", FindOptions("a.c")).size)
    }

    @Test fun regexMode() {
        assertEquals(2, matches("a1 b22", FindOptions("[a-z]\\d+", regex = true)).size)
    }

    @Test fun wholeWord() {
        assertEquals(1, matches("cat concat cat's", FindOptions("cat", wholeWord = true)).size.let { it - 1 })
        assertEquals(listOf(Match(0, 3), Match(11, 14)), matches("cat concat cat", FindOptions("cat", wholeWord = true)))
    }

    @Test fun invalidRegexIsAFailureNotACrash() {
        assertTrue(FindReplace.findAll("x", FindOptions("(", regex = true)).isFailure)
    }

    @Test fun emptyQueryIsAFailure() {
        assertTrue(FindReplace.findAll("x", FindOptions("")).isFailure)
    }

    @Test fun zeroWidthMatchesAreSkipped() {
        assertTrue(matches("abc", FindOptions("x*", regex = true)).isEmpty())
    }

    @Test fun nextAndPreviousWrap() {
        val m = listOf(Match(2, 3), Match(8, 9))
        assertEquals(Match(8, 9), FindReplace.next(m, 3))
        assertEquals(Match(2, 3), FindReplace.next(m, 9))
        assertEquals(Match(2, 3), FindReplace.previous(m, 8))
        assertEquals(Match(8, 9), FindReplace.previous(m, 2))
        assertNull(FindReplace.next(emptyList(), 0))
    }

    @Test fun replaceAllLiteralKeepsDollarSigns() {
        val (out, n) = FindReplace.replaceAll("a a", FindOptions("a"), "\$1").getOrThrow()
        assertEquals("\$1 \$1", out)
        assertEquals(2, n)
    }

    @Test fun replaceAllRegexGroups() {
        val (out, n) = FindReplace.replaceAll("k=v j=w", FindOptions("(\\w)=(\\w)", regex = true), "$2=$1").getOrThrow()
        assertEquals("v=k w=j", out)
        assertEquals(2, n)
    }

    @Test fun replaceWithMissingGroupFails() {
        assertTrue(FindReplace.replaceAll("a", FindOptions("a", regex = true), "$3").isFailure)
    }

    @Test fun replaceNothing() {
        val (out, n) = FindReplace.replaceAll("abc", FindOptions("z"), "y").getOrThrow()
        assertEquals("abc", out); assertEquals(0, n)
    }
}
