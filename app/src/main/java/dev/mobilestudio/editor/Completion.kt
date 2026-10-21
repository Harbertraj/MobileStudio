package dev.mobilestudio.editor

data class Suggestion(val text: String, val kind: Kind) {
    enum class Kind { KEYWORD, WORD }
}

/** What would be replaced: text in [start, end) is the prefix being completed. */
data class CompletionContext(val prefix: String, val start: Int, val end: Int)

/**
 * Lightweight completion: language keywords plus identifiers already present in the file.
 * This is not semantic completion (no types, no imports); it saves typing, nothing more.
 */
object Completion {

    private val identChar = { c: Char -> c.isLetterOrDigit() || c == '_' }
    private val wordRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private val keywordsByExt: Map<String, Set<String>> = run {
        val kotlin = setOf(
            "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when",
            "for", "while", "return", "break", "continue", "try", "catch", "finally", "throw", "override",
            "private", "public", "protected", "internal", "open", "abstract", "sealed", "data", "enum",
            "companion", "init", "suspend", "lateinit", "const", "by", "null", "true", "false", "this", "super",
        )
        val java = setOf(
            "package", "import", "class", "interface", "enum", "extends", "implements", "new", "public",
            "private", "protected", "static", "final", "abstract", "void", "int", "long", "double", "float",
            "boolean", "char", "if", "else", "switch", "case", "default", "for", "while", "return", "break",
            "continue", "try", "catch", "finally", "throw", "throws", "this", "super", "null", "true", "false",
        )
        mapOf("kt" to kotlin, "kts" to kotlin, "gradle" to kotlin, "java" to java)
    }

    fun contextAt(text: String, cursor: Int): CompletionContext? {
        if (cursor !in 0..text.length) return null
        var start = cursor
        while (start > 0 && identChar(text[start - 1])) start--
        if (start == cursor) return null
        if (text[start].isDigit()) return null
        return CompletionContext(text.substring(start, cursor), start, cursor)
    }

    fun suggest(text: String, cursor: Int, extension: String, limit: Int = 8): List<Suggestion> {
        val ctx = contextAt(text, cursor) ?: return emptyList()
        val prefix = ctx.prefix
        val keywords = keywordsByExt[extension.lowercase()].orEmpty()

        val out = LinkedHashMap<String, Suggestion>()
        for (k in keywords.sorted()) {
            if (k.startsWith(prefix, ignoreCase = false) && k != prefix) {
                out[k] = Suggestion(k, Suggestion.Kind.KEYWORD)
            }
        }
        // Count words so common identifiers rank first; exclude the word being typed itself.
        val counts = HashMap<String, Int>()
        for (m in wordRegex.findAll(text)) {
            if (m.range.first == ctx.start) continue
            counts.merge(m.value, 1, Int::plus)
        }
        counts.entries
            .filter { it.key.startsWith(prefix) && it.key != prefix && it.key !in out }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .forEach { out[it.key] = Suggestion(it.key, Suggestion.Kind.WORD) }

        return out.values.take(limit)
    }

    fun accept(text: String, ctx: CompletionContext, suggestion: Suggestion): EditResult {
        val newText = text.substring(0, ctx.start) + suggestion.text + text.substring(ctx.end)
        return EditResult(newText, ctx.start + suggestion.text.length)
    }
}
