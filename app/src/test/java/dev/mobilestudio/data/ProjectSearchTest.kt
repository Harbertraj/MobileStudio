package dev.mobilestudio.data

import dev.mobilestudio.editor.FindOptions
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ProjectSearchTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File.createTempFile("pstudio", "").also { it.delete(); it.mkdirs() }
        File(dir, "a.kt").writeText("val needle = 1\nfoo\nneedle again needle")
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.txt").writeText("no match here\nNEEDLE")
        File(dir, "build").mkdirs()
        File(dir, "build/gen.kt").writeText("needle")
        File(dir, ".git").mkdirs()
        File(dir, ".git/config").writeText("needle")
        File(dir, "bin.dat").writeBytes(byteArrayOf(110, 0, 110))
    }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun findsHitsWithPositionsAndSkipsJunk() {
        val hits = ProjectSearch.search(dir, FindOptions("needle")).getOrThrow()
        assertEquals(
            listOf("a.kt:1:5", "a.kt:3:1", "a.kt:3:14", "b.txt:2:1"),
            hits.map { "${it.file.name}:${it.line}:${it.column}" },
        )
    }

    @Test fun caseSensitiveNarrows() {
        val hits = ProjectSearch.search(dir, FindOptions("needle", caseSensitive = true)).getOrThrow()
        assertEquals(3, hits.size)
    }

    @Test fun maxHitsCaps() {
        assertEquals(2, ProjectSearch.search(dir, FindOptions("needle"), maxHits = 2).getOrThrow().size)
    }

    @Test fun badRegexFails() {
        assertTrue(ProjectSearch.search(dir, FindOptions("(", regex = true)).isFailure)
    }
}
