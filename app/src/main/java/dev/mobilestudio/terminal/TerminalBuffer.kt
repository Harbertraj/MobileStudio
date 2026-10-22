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

    /** `build` / `build debug`. */
    data object Build : BuiltinCommand

    /** `build release`. */
    data object BuildRelease : BuiltinCommand

    /** Opens the system installer for the last APK that was built. */
    data object Install : BuiltinCommand
    data object Help : BuiltinCommand

    /** Built-in git (there is no git binary on the phone); [args] is everything after `git`. */
    data class Git(val args: List<String>) : BuiltinCommand

    /** `toolchain status|setup|android-jar <api>|aapt2-url <url>|...`. */
    data class ToolchainCmd(val args: List<String>) : BuiltinCommand

    /** `keystore info|export`. */
    data class Keystore(val args: List<String>) : BuiltinCommand

    /** A built-in that cannot work the way it was typed (for example git inside a pipe); [message] says why. */
    data class Invalid(val message: String) : BuiltinCommand
}

object TerminalCommands {
    fun parse(line: String): BuiltinCommand? {
        val trimmed = line.trim()
        val words = CommandLine.split(trimmed) ?: return null
        val first = words.firstOrNull() ?: return null
        val args = words.drop(1)
        val builtin = first in setOf("clear", "build", "install", "help", "git", "toolchain", "keystore")
        if (builtin && CommandLine.hasShellOperators(trimmed)) {
            // `clear; ls` is a plain shell line; only the IDE-only commands explain why they cannot be chained.
            return if (first in setOf("git", "toolchain", "keystore", "build"))
                BuiltinCommand.Invalid("'$first' is built into MobileStudio and cannot be used with pipes, redirects, && or ;. Run it on its own line.")
            else null
        }
        return when (first) {
            "clear" -> if (args.isEmpty()) BuiltinCommand.Clear else null
            "build" -> when {
                args.isEmpty() -> BuiltinCommand.Build
                args == listOf("debug") -> BuiltinCommand.Build
                args == listOf("release") -> BuiltinCommand.BuildRelease
                else -> null
            }
            "install" -> if (args.isEmpty()) BuiltinCommand.Install else null
            "help" -> if (args.isEmpty()) BuiltinCommand.Help else null
            "git" -> BuiltinCommand.Git(args)
            "toolchain" -> BuiltinCommand.ToolchainCmd(args)
            "keystore" -> BuiltinCommand.Keystore(args)
            else -> null
        }
    }

    const val HELP = """Built-in commands
  build [debug|release]   build an APK (release signs with your key; see 'keystore')
  install                 install the last APK that was built
  toolchain               status | setup | android-jar <api> | aapt2-url <url> | reset-url
  keystore                info | export   (release signing key)
  git <command>           built-in git: init clone status add rm mv commit log diff show
                          branch checkout switch restore merge rebase fetch pull push
                          remote reset stash tag cherry-pick revert clean config ls-files
  git auth                login [host] <user> <token> | logout [host] | status
  clear                   clear this screen
Everything else runs in the shell (ls, cat, cd, ...). Run 'git help' for details."""
}
