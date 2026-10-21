package dev.mobilestudio.editor

import org.junit.Assert.*
import org.junit.Test

class CompletionTest {
    @Test fun noPrefixNoSuggestions() {
        assertTrue(Completion.suggest("foo ", 4, "kt").isEmpty())
    }

    @Test fun keywordsAndFileWordsAreSuggested() {
        val text = "val counter = 1\nfun f() { cou"
        val s = Completion.suggest(text, text.length, "kt").map { it.text }
        assertEquals(listOf("counter"), s)
        val s2 = Completion.suggest("fun f() { re", 12, "kt").map { it.text }
        assertTrue("return" in s2)
    }

    @Test fun wordBeingTypedIsNotSuggestedToItself() {
        assertTrue(Completion.suggest("abc", 3, "txt").isEmpty())
    }

    @Test fun frequentWordsRankFirst() {
        val text = "alpha alphabet alphabet alphabet al"
        val s = Completion.suggest(text, text.length, "txt").map { it.text }
        assertEquals(listOf("alphabet", "alpha"), s)
    }

    @Test fun keywordsComeBeforeWords() {
        val text = "returned re"
        val s = Completion.suggest(text, text.length, "kt")
        assertEquals("return", s.first().text)
        assertEquals(Suggestion.Kind.KEYWORD, s.first().kind)
        assertTrue(s.any { it.text == "returned" })
    }

    @Test fun acceptReplacesPrefix() {
        val text = "x = cou"
        val ctx = Completion.contextAt(text, text.length)!!
        val r = Completion.accept(text, ctx, Suggestion("counter", Suggestion.Kind.WORD))
        assertEquals("x = counter", r.text)
        assertEquals(r.text.length, r.cursor)
    }

    @Test fun acceptInMiddleOfText() {
        val text = "ab cd"
        val ctx = Completion.contextAt(text, 2)!! // "ab"
        val r = Completion.accept(text, ctx, Suggestion("abc", Suggestion.Kind.WORD))
        assertEquals("abc cd", r.text)
        assertEquals(3, r.cursor)
    }

    @Test fun digitsDoNotStartAnIdentifier() {
        assertNull(Completion.contextAt("12", 2))
    }

    @Test fun limitIsHonoured() {
        val text = (1..20).joinToString(" ") { "item$it" } + " it"
        assertEquals(5, Completion.suggest(text, text.length, "txt", limit = 5).size)
    }
}
