package dev.mobilestudio.terminal

/**
 * Scrollback for the terminal view. Output arrives in arbitrary chunks; this removes ANSI escape
 * sequences (even when split across chunks), applies carriage returns the way progress output expects,
 * and keeps memory bounded.
 */
class TerminalBuffer(private val maxChars: Int = 100_000) {

    private val sb = StringBuilder()
    private var carry = "" // an escape sequence that was cut off at the end of the previous chunk

    /** Safe to call from any thread: the shell's reader thread and the UI thread both write to it. */
    val text: String
        @Synchronized get() = sb.toString()

    @Synchronized
    fun clear() { sb.setLength(0); carry = "" }

    @Synchronized
    fun append(chunk: String) {
        val input = carry + chunk
        carry = ""
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '\u001B' -> {
                    val end = escapeEnd(input, i)
                    if (end < 0) { carry = input.substring(i); return } // incomplete: wait for more
                    i = end
                    continue
                }
                c == '\r' -> {
                    when {
                        i + 1 == input.length -> { carry = "\r"; trim(); return } // maybe half of CRLF: decide next chunk
                        input[i + 1] != '\n' -> rewindLine()                      // lone CR: redraw the line
                    }
                }
                c == '\b' -> if (sb.isNotEmpty() && sb.last() != '\n') sb.setLength(sb.length - 1)
                c == '\u0007' -> Unit // bell
                else -> sb.append(c)
            }
            i++
        }
        trim()
    }

    private fun rewindLine() {
        val nl = sb.lastIndexOf("\n")
        sb.setLength(nl + 1)
    }

    /** Index just past the escape sequence starting at [start], or -1 when it is incomplete. */
    private fun escapeEnd(s: String, start: Int): Int {
        if (start + 1 >= s.length) return -1
        return when (s[start + 1]) {
            '[' -> { // CSI: ESC [ params intermediates final(@-~)
                var j = start + 2
                while (j < s.length && s[j] in '0'..'?') j++
                while (j < s.length && s[j] in ' '..'/') j++
                if (j >= s.length) -1 else j + 1
            }
            ']' -> { // OSC: ESC ] ... BEL or ESC \
                var j = start + 2
                while (j < s.length) {
                    if (s[j] == '\u0007') return j + 1
                    if (s[j] == '\u001B') return if (j + 1 < s.length) j + 2 else -1
                    j++
                }
                -1
            }
            else -> start + 2 // two-character escape
        }
    }

    private fun trim() {
        if (sb.length <= maxChars) return
        val cut = sb.length - maxChars
        val nl = sb.indexOf("\n", cut)
        sb.delete(0, if (nl in 0 until sb.length - 1) nl + 1 else cut)
    }
}

/** Up/down navigation through previously entered commands. */
class CommandHistory(private val max: Int = 100) {
    private val items = ArrayList<String>()
    private var cursor = 0 // == items.size means "not browsing"

    fun add(command: String) {
        val c = command.trim()
        if (c.isNotEmpty() && items.lastOrNull() != c) {
            items += c
            if (items.size > max) items.removeAt(0)
        }
        cursor = items.size
    }

    /** Older command, or null when there is no history. Stays on the oldest at the end of the list. */
    fun previous(): String? {
        if (items.isEmpty()) return null
        if (cursor > 0) cursor--
        return items[cursor]
    }

    /** Newer command; null once you move past the newest (meaning: clear the input). */
    fun next(): String? {
        if (cursor >= items.size - 1) { cursor = items.size; return null }
        cursor++
        return items[cursor]
    }
}

/** Commands handled by the IDE instead of the shell. */
sealed interface BuiltinCommand {
    data object Clear : BuiltinCommand
    data object Build : BuiltinCommand
}

object TerminalCommands {
    fun parse(line: String): BuiltinCommand? = when (line.trim()) {
        "clear" -> BuiltinCommand.Clear
        "build" -> BuiltinCommand.Build
        else -> null
    }
}
