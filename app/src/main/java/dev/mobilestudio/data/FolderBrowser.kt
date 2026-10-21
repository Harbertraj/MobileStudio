package dev.mobilestudio.data

import java.io.File

/** Directory navigation for the "Open folder" dialog. Pure java.io, no Android types. */
object FolderBrowser {

    /** Sub-directories of [dir], case-insensitively sorted. Hidden folders are skipped unless [showHidden]. */
    fun list(dir: File, showHidden: Boolean = false): Result<List<File>> = runCatching {
        require(dir.isDirectory) { "${dir.path} is not a folder" }
        val children = dir.listFiles() ?: error("Cannot read ${dir.path} (permission denied?)")
        children
            .filter { it.isDirectory && (showHidden || !it.name.startsWith(".")) }
            .sortedBy { it.name.lowercase() }
    }

    /** Parent of [dir], or null when [dir] is [top] (or has no parent). Keeps the dialog from climbing above the storage root. */
    fun parentOf(dir: File, top: File?): File? {
        if (top != null && dir.canonicalPath == top.canonicalPath) return null
        return dir.parentFile
    }

    /** Opening the storage root (or the filesystem root) as a project would make search and the tree crawl everything. */
    fun isTooBroad(dir: File, storageRoot: File): Boolean {
        val canonical = dir.canonicalFile
        return canonical == storageRoot.canonicalFile || canonical.parentFile == null
    }

    /** True when [dir] has the layout this app can build (module in `app/` or in the folder itself). */
    fun looksLikeAndroidProject(dir: File): Boolean = ModuleLocator.find(dir) != null
}

/** Remembers the last few opened folders. Persisted as newline separated paths. */
object RecentFolders {

    const val MAX = 10

    fun add(current: List<String>, path: String, max: Int = MAX): List<String> =
        (listOf(path) + current.filter { it != path }).take(max)

    fun encode(paths: List<String>): String = paths.joinToString("\n")

    fun decode(raw: String?): List<String> =
        raw.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Drops entries that no longer exist so the list never offers a dead folder. */
    fun prune(paths: List<String>): List<String> = paths.filter { File(it).isDirectory }
}

/** Finds the Android application module inside a project folder. */
object ModuleLocator {

    private fun isModule(dir: File): Boolean =
        File(dir, "src/main/AndroidManifest.xml").isFile &&
            (File(dir, "build.gradle.kts").isFile || File(dir, "build.gradle").isFile)

    /** `<project>/app` first (standard layout), then the project folder itself (single-module layout). */
    fun find(project: File): File? = listOf(File(project, "app"), project).firstOrNull { isModule(it) }
}
