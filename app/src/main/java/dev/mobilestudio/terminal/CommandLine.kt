package dev.mobilestudio.terminal

/** Shell-style splitting of a command line, used for the commands the IDE handles itself (git, build, ...). */
object CommandLine {

    /**
     * Splits [line] into words the way a POSIX shell would: whitespace separates words, single quotes are literal,
     * double quotes allow \" and \\, and a backslash outside quotes escapes the next character.
     * Returns null when a quote is left open.
     */
    fun split(line: String): List<String>? {
        val words = mutableListOf<String>()
        val cur = StringBuilder()
        var inWord = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\'' -> {
                    val end = line.indexOf('\'', i + 1)
                    if (end < 0) return null
                    cur.append(line, i + 1, end); inWord = true; i = end
                }
                c == '"' -> {
                    i++; inWord = true
                    while (true) {
                        if (i >= line.length) return null
                        val d = line[i]
                        if (d == '"') break
                        if (d == '\\' && i + 1 < line.length && line[i + 1] in "\"\\$`") { cur.append(line[i + 1]); i += 2; continue }
                        cur.append(d); i++
                    }
                }
                c == '\\' -> {
                    if (i + 1 < line.length) { cur.append(line[i + 1]); inWord = true; i++ }
                }
                c.isWhitespace() -> if (inWord) { words += cur.toString(); cur.setLength(0); inWord = false }
                else -> { cur.append(c); inWord = true }
            }
            i++
        }
        if (inWord) words += cur.toString()
        return words
    }

    /** True when [line] uses a pipe, redirect, `&&`, `;`, background `&`, or substitution outside quotes. */
    fun hasShellOperators(line: String): Boolean {
        var i = 0
        while (i < line.length) {
            when (val c = line[i]) {
                '\'' -> { val end = line.indexOf('\'', i + 1); if (end < 0) return false; i = end }
                '"' -> {
                    i++
                    while (i < line.length && line[i] != '"') {
                        if (line[i] == '\\') i++
                        else if (line[i] == '`' || (line[i] == '$' && line.getOrNull(i + 1) == '(')) return true
                        i++
                    }
                }
                '\\' -> i++
                '|', '&', ';', '<', '>', '`' -> return true
                '$' -> if (line.getOrNull(i + 1) == '(') return true
                else -> Unit
            }
            i++
        }
        return false
    }
}
