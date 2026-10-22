package dev.mobilestudio.terminal

import java.io.File

/** Path handling for the built-in git: users type paths relative to the shell's folder, git wants them relative to the repository root. */
object GitPaths {

    /**
     * [arg] as typed in [cwd], expressed relative to [workTree] with `/` separators.
     * "." (or the work tree itself) becomes "." so it can be used as "everything". Returns null when the path is outside the work tree.
     */
    fun repoRelative(workTree: File, cwd: File, arg: String): String? {
        val target = (if (File(arg).isAbsolute) File(arg) else File(cwd, arg)).let { normalize(it) }
        val root = normalize(workTree)
        if (target == root) return "."
        val rootPrefix = root.path.trimEnd('/') + "/"
        if (!target.path.startsWith(rootPrefix)) return null
        return target.path.removePrefix(rootPrefix).replace(File.separatorChar, '/')
    }

    /** A repository path as it should be shown to someone standing in [cwd]. */
    fun display(workTree: File, cwd: File, repoPath: String): String {
        val abs = File(workTree, repoPath)
        val rel = runCatching { abs.relativeTo(normalize(cwd)).path }.getOrDefault(repoPath)
        return rel.replace(File.separatorChar, '/').ifEmpty { "." }
    }

    /** Removes "." and ".." without touching the disk (a deleted file must still resolve). */
    private fun normalize(f: File): File {
        val parts = ArrayList<String>()
        for (p in f.absoluteFile.path.split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += p
            }
        }
        return File("/" + parts.joinToString("/"))
    }
}
