package dev.mobilestudio.data

import java.io.File

data class TreeNode(val file: File, val depth: Int) {
    val isDir: Boolean get() = file.isDirectory
    val name: String get() = file.name
}

/**
 * File operations for projects stored under [root].
 * Every path is checked to stay inside [root] before it is touched.
 */
class ProjectRepository(private val root: File) {

    init {
        root.mkdirs()
    }

    private val rootCanonical: String = root.canonicalPath

    private fun requireInside(file: File): File {
        val canonical = file.canonicalPath
        require(canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)) {
            "Path is outside the workspace"
        }
        return file
    }

    private fun requireValidName(name: String) {
        require(name.isNotBlank()) { "Name is empty" }
        require('/' !in name && '\\' !in name && name != "." && name != "..") { "Invalid name: $name" }
    }

    fun projects(): List<File> =
        root.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun createProject(name: String, packageName: String): Result<File> = runCatching {
        requireValidName(name)
        require(PACKAGE_REGEX.matches(packageName)) { "Invalid package name: $packageName" }
        val dir = requireInside(File(root, name))
        require(!dir.exists()) { "Project already exists" }
        Templates.emptyActivity(name, packageName).forEach { (path, content) ->
            val target = requireInside(File(dir, path))
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
        dir
    }

    fun deleteProject(project: File): Result<Unit> = runCatching {
        requireInside(project)
        require(project.parentFile?.canonicalPath == rootCanonical) { "Not a project directory" }
        project.deleteRecursively()
        Unit
    }

    /** Flattened visible tree: directories first, only expanded directories are descended. */
    fun tree(project: File, expanded: Set<String>): List<TreeNode> {
        val out = mutableListOf<TreeNode>()
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles()?.sortedWith(
                compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })
            ) ?: return
            for (child in children) {
                out += TreeNode(child, depth)
                if (child.isDirectory && child.path in expanded) walk(child, depth + 1)
            }
        }
        walk(project, 0)
        return out
    }

    fun read(file: File): Result<String> = runCatching {
        requireInside(file)
        require(file.length() <= MAX_EDITABLE_BYTES) { "File is too large to edit (${file.length()} bytes)" }
        file.readText()
    }

    fun write(file: File, text: String): Result<Unit> = runCatching {
        requireInside(file)
        file.writeText(text)
    }

    fun createFile(parent: File, name: String): Result<File> = runCatching {
        requireValidName(name)
        requireInside(parent)
        val f = requireInside(File(parent, name))
        require(!f.exists()) { "$name already exists" }
        f.createNewFile()
        f
    }

    fun createDirectory(parent: File, name: String): Result<File> = runCatching {
        requireValidName(name)
        requireInside(parent)
        val d = requireInside(File(parent, name))
        require(!d.exists()) { "$name already exists" }
        d.mkdirs()
        d
    }

    fun rename(file: File, newName: String): Result<File> = runCatching {
        requireValidName(newName)
        requireInside(file)
        val target = requireInside(File(file.parentFile, newName))
        require(!target.exists()) { "$newName already exists" }
        check(file.renameTo(target)) { "Rename failed" }
        target
    }

    fun delete(file: File): Result<Unit> = runCatching {
        requireInside(file)
        require(file.canonicalPath != rootCanonical) { "Cannot delete the workspace" }
        check(file.deleteRecursively()) { "Delete failed" }
    }

    companion object {
        const val MAX_EDITABLE_BYTES = 2L * 1024 * 1024
        private val PACKAGE_REGEX = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
    }
}
