package dev.mobilestudio.editor

data class Diagnostic(val line: Int, val column: Int, val message: String)

/**
 * Cheap structural checks that run on every edit: unbalanced brackets (Kotlin/Java/Gradle)
 * and mismatched tags (XML). These are not compiler errors; a clean result does not mean
 * the file compiles.
 */
object Diagnostics {

    fun check(text: String, extension: String): List<Diagnostic> = when (extension.lowercase()) {
        "kt", "kts", "gradle", "java" -> brackets(text)
        "xml" -> xmlTags(text)
        else -> emptyList()
    }

    private class Pos(val line: Int, val col: Int)

    private fun brackets(text: String): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val stack = ArrayList<Pair<Char, Pos>>()
        val closeToOpen = mapOf(')' to '(', ']' to '[', '}' to '{')
        var line = 1
        var col = 1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> { line++; col = 1; i++; continue }
                // line comment
                c == '/' && text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') { i++; col++ }
                    continue
                }
                // block comment
                c == '/' && text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2)
                    val stop = if (end < 0) text.length else end + 2
                    while (i < stop) {
                        if (text[i] == '\n') { line++; col = 1 } else col++
                        i++
                    }
                    continue
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    val startLine = line
                    val startCol = col
                    i++; col++
                    var closed = false
                    while (i < text.length) {
                        val d = text[i]
                        if (d == '\\' && i + 1 < text.length) { i += 2; col += 2; continue }
                        if (d == '\n') break // strings do not span lines (raw strings are not handled)
                        i++; col++
                        if (d == quote) { closed = true; break }
                    }
                    if (!closed) out += Diagnostic(startLine, startCol, "Unterminated string")
                    continue
                }
                c == '(' || c == '[' || c == '{' -> stack += c to Pos(line, col)
                c in closeToOpen -> {
                    val top = stack.lastOrNull()
                    if (top == null || top.first != closeToOpen.getValue(c)) {
                        out += Diagnostic(line, col, "Unmatched '$c'")
                    } else {
                        stack.removeAt(stack.lastIndex)
                    }
                }
            }
            i++; col++
        }
        for ((ch, pos) in stack) out += Diagnostic(pos.line, pos.col, "Unclosed '$ch'")
        return out.sortedWith(compareBy({ it.line }, { it.column }))
    }

    private val tagRegex = Regex("""<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<(/?)([A-Za-z_][\w:.\-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(/?)>""")

    private fun xmlTags(text: String): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val stack = ArrayList<Pair<String, Int>>() // name to offset
        for (m in tagRegex.findAll(text)) {
            val name = m.groupValues[2]
            if (name.isEmpty()) continue // comment or processing instruction
            val closing = m.groupValues[1] == "/"
            val selfClosing = m.groupValues[4] == "/"
            when {
                closing -> {
                    val top = stack.lastOrNull()
                    if (top == null || top.first != name) {
                        out += at(text, m.range.first, "Unexpected closing tag </$name>")
                    } else {
                        stack.removeAt(stack.lastIndex)
                    }
                }
                !selfClosing -> stack += name to m.range.first
            }
        }
        for ((name, offset) in stack) out += at(text, offset, "Unclosed tag <$name>")
        return out.sortedWith(compareBy({ it.line }, { it.column }))
    }

    private fun at(text: String, offset: Int, message: String): Diagnostic {
        var line = 1
        var lastBreak = -1
        for (i in 0 until offset) if (text[i] == '\n') { line++; lastBreak = i }
        return Diagnostic(line, offset - lastBreak, message)
    }
}
