package dev.mobilestudio.terminal

import java.io.File

/**
 * Follows the shell's working directory. The shell is a plain `sh` process, and the commands the IDE runs
 * itself (git) need to know where it currently is. Only simple `cd` lines are understood; a `cd` inside a
 * compound command is left to the shell and is not tracked.
 */
class ShellCwd(start: File, private val home: File) {

    var current: File = start
        private set
    private var previous: File = start

    /** Updates the tracked directory when [line] is a successful plain `cd`. Returns true if it was a `cd` line. */
    fun observe(line: String): Boolean {
        if (CommandLine.hasShellOperators(line)) return false
        val words = CommandLine.split(line) ?: return false
        if (words.firstOrNull() != "cd" || words.size > 2) return false
        val target = when (val arg = words.getOrNull(1)) {
            null, "~" -> home
            "-" -> previous
            else -> when {
                arg.startsWith("~/") -> File(home, arg.substring(2))
                File(arg).isAbsolute -> File(arg)
                else -> File(current, arg)
            }
        }
        val resolved = runCatching { target.canonicalFile }.getOrNull() ?: return true
        if (resolved.isDirectory) { previous = current; current = resolved }
        return true
    }

    /** Back to [dir] (a new shell was started there). */
    fun reset(dir: File) { current = dir; previous = dir }
}
