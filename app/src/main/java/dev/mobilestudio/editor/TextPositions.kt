package dev.mobilestudio.editor

object TextPositions {

    /** Offset of 1-based [line]/[column] in [text], clamped to the end of that line (or the text). */
    fun offsetOf(text: String, line: Int, column: Int): Int {
        var offset = 0
        var current = 1
        while (current < line) {
            val nl = text.indexOf('\n', offset)
            if (nl < 0) return text.length
            offset = nl + 1
            current++
        }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return (offset + (column - 1).coerceAtLeast(0)).coerceAtMost(lineEnd)
    }

    /** 0-based line index containing [offset]. */
    fun lineIndexOf(text: String, offset: Int): Int {
        val end = offset.coerceIn(0, text.length)
        var n = 0
        for (i in 0 until end) if (text[i] == '\n') n++
        return n
    }
}
