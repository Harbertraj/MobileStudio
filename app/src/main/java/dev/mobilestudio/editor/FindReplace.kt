package dev.mobilestudio.editor

data class FindOptions(
    val query: String,
    val caseSensitive: Boolean = false,
    val regex: Boolean = false,
    val wholeWord: Boolean = false,
)

/** Half-open range [start, end) in the searched text. */
data class Match(val start: Int, val end: Int)

object FindReplace {

    /** Returns a compiled pattern, or a failure with the reason when the query is invalid. */
    fun compile(options: FindOptions): Result<Regex> = runCatching {
        require(options.query.isNotEmpty()) { "Empty query" }
        var source = if (options.regex) options.query else Regex.escape(options.query)
        if (options.wholeWord) source = "\\b(?:$source)\\b"
        val flags = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        Regex(source, flags)
    }

    fun findAll(text: String, options: FindOptions, maxMatches: Int = 10_000): Result<List<Match>> =
        compile(options).map { re ->
            val out = ArrayList<Match>()
            for (m in re.findAll(text)) {
                if (m.value.isEmpty()) continue // zero-width matches are not useful to navigate
                out += Match(m.range.first, m.range.last + 1)
                if (out.size >= maxMatches) break
            }
            out
        }

    /** Next match at or after [from], wrapping to the first one; null if there are none. */
    fun next(matches: List<Match>, from: Int): Match? =
        matches.firstOrNull { it.start >= from } ?: matches.firstOrNull()

    /** Previous match strictly before [from], wrapping to the last one. */
    fun previous(matches: List<Match>, from: Int): Match? =
        matches.lastOrNull { it.start < from } ?: matches.lastOrNull()

    /**
     * Replaces every match. With [options].regex, `$1`-style group references work in [replacement];
     * in plain mode the replacement is literal.
     */
    fun replaceAll(text: String, options: FindOptions, replacement: String): Result<Pair<String, Int>> =
        compile(options).mapCatching { re ->
            var count = 0
            val literal = if (options.regex) replacement else Regex.escapeReplacement(replacement)
            val out = re.replace(text) { m ->
                if (m.value.isEmpty()) {
                    m.value
                } else {
                    count++
                    // Resolve group refs ourselves so an invalid reference fails instead of mangling text.
                    expand(m, literal)
                }
            }
            out to count
        }

    private fun expand(m: MatchResult, template: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                c == '\\' && i + 1 < template.length -> { sb.append(template[i + 1]); i += 2 }
                c == '$' && i + 1 < template.length && template[i + 1].isDigit() -> {
                    val g = template[i + 1].digitToInt()
                    require(g < m.groupValues.size) { "No group $g in pattern" }
                    sb.append(m.groupValues[g]); i += 2
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
