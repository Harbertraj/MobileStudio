package dev.mobilestudio.editor

/** Text plus cursor (collapsed selection) offset. */
data class EditResult(val text: String, val cursor: Int)

/**
 * Typing assistance applied to a single change. [oldText]/[oldCursor] is the state before the
 * user's keystroke, [newText]/[newCursor] the state the text field reports after it.
 * Returns null when no assistance applies and the keystroke should be kept as is.
 */
object AutoEdit {

    private const val INDENT = "    "
    private val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"')

    fun apply(oldText: String, oldCursor: Int, newText: String, newCursor: Int): EditResult? {
        // Only handle a single inserted character with a collapsed cursor right after it.
        if (newText.length != oldText.length + 1) return null
        if (newCursor != oldCursor + 1 || newCursor < 1 || newCursor > newText.length) return null
        if (newText.substring(0, oldCursor) != oldText.substring(0, oldCursor)) return null
        if (newText.substring(newCursor) != oldText.substring(oldCursor)) return null

        val typed = newText[newCursor - 1]
        return when {
            typed == '\n' -> newline(newText, newCursor)
            typed in pairs -> pair(newText, newCursor, typed)
            typed in pairs.values -> skipClosing(oldText, oldCursor, typed)
            else -> null
        }
    }

    private fun lineStart(text: String, offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1

    private fun newline(text: String, cursor: Int): EditResult {
        // The line that was just ended is the one before the inserted newline.
        val breakAt = cursor - 1
        val prevStart = lineStart(text, breakAt)
        val prevLine = text.substring(prevStart, breakAt)
        val baseIndent = prevLine.takeWhile { it == ' ' || it == '\t' }
        val trimmed = prevLine.trimEnd()
        val opensBlock = trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[")
        val after = text.getOrNull(cursor)

        // "{|}" + Enter -> put the closing brace on its own line, cursor on an indented blank line.
        if (opensBlock && after != null && after == closerFor(trimmed.last())) {
            val inner = baseIndent + INDENT
            val insert = inner + "\n" + baseIndent
            return EditResult(
                text.substring(0, cursor) + insert + text.substring(cursor),
                cursor + inner.length,
            )
        }
        val indent = if (opensBlock) baseIndent + INDENT else baseIndent
        if (indent.isEmpty()) return EditResult(text, cursor)
        return EditResult(text.substring(0, cursor) + indent + text.substring(cursor), cursor + indent.length)
    }

    private fun closerFor(open: Char): Char = pairs.getValue(open)

    private fun pair(text: String, cursor: Int, typed: Char): EditResult? {
        val after = text.getOrNull(cursor)
        // Don't auto-pair a quote right after a word character (e.g. an apostrophe-like typing).
        if (typed == '"') {
            val before = text.getOrNull(cursor - 2)
            if (before != null && before.isLetterOrDigit()) return null
        }
        // Only auto-close when the next character is whitespace, a closer, or end of text.
        if (after != null && !after.isWhitespace() && after !in pairs.values) return null
        val close = pairs.getValue(typed)
        return EditResult(text.substring(0, cursor) + close + text.substring(cursor), cursor)
    }

    private fun skipClosing(oldText: String, oldCursor: Int, typed: Char): EditResult? {
        // Typing a closer when that same closer is already next: step over it instead of duplicating.
        return if (oldText.getOrNull(oldCursor) == typed) EditResult(oldText, oldCursor + 1) else null
    }
}
