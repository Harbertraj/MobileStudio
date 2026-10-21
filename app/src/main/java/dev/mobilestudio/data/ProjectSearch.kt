package dev.mobilestudio.data

import dev.mobilestudio.editor.FindOptions
import dev.mobilestudio.editor.FindReplace
import java.io.File

data class SearchHit(val file: File, val line: Int, val column: Int, val preview: String)

/** Text search across a project directory. Skips build output, VCS data and binary files. */
object ProjectSearch {

    private val skipDirs = setOf(".git", ".gradle", "build", ".idea")
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024

    fun search(project: File, options: FindOptions, maxHits: Int = 500): Result<List<SearchHit>> {
        val re = FindReplace.compile(options).getOrElse { return Result.failure(it) }
        val hits = ArrayList<SearchHit>()
        val files = project.walkTopDown()
            .onEnter { it == project || it.name !in skipDirs }
            .filter { it.isFile && it.length() <= MAX_FILE_BYTES }
            .sortedBy { it.path }

        for (file in files) {
            if (hits.size >= maxHits) break
            val lines = readTextOrNull(file) ?: continue
            lines.forEachIndexed { index, line ->
                if (hits.size >= maxHits) return@forEachIndexed
                for (m in re.findAll(line)) {
                    if (m.value.isEmpty()) continue
                    hits += SearchHit(file, index + 1, m.range.first + 1, line.trim().take(160))
                    if (hits.size >= maxHits) break
                }
            }
        }
        return Result.success(hits)
    }

    private fun readTextOrNull(file: File): List<String>? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.any { it == 0.toByte() }) return null // looks binary
        return String(bytes, Charsets.UTF_8).lines()
    }
}
