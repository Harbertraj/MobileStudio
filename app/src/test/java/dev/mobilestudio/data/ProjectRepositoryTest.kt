package dev.mobilestudio.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ProjectRepositoryTest {
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var repo: ProjectRepository

    @Before fun setUp() {
        base = File.createTempFile("mstudio", "").also { it.delete(); it.mkdirs() }
        root = File(base, "workspace")
        repo = ProjectRepository(root)
    }
    @After fun tearDown() { base.deleteRecursively() }

    @Test fun createProjectWritesTemplate() {
        val dir = repo.createProject("Demo", "com.example.demo").getOrThrow()
        assertTrue(File(dir, "app/src/main/AndroidManifest.xml").isFile)
        assertTrue(File(dir, "app/src/main/java/com/example/demo/MainActivity.kt").readText().contains("package com.example.demo"))
        assertEquals(listOf(dir), repo.projects())
    }

    @Test fun rejectsBadNamesAndPackages() {
        assertTrue(repo.createProject("", "com.a.b").isFailure)
        assertTrue(repo.createProject("../evil", "com.a.b").isFailure)
        assertTrue(repo.createProject("a/b", "com.a.b").isFailure)
        assertTrue(repo.createProject("ok", "Com.Bad").isFailure)
        assertTrue(repo.createProject("ok", "single").isFailure)
        assertTrue(repo.projects().isEmpty())
    }

    @Test fun duplicateProjectFails() {
        repo.createProject("P", "com.a.b").getOrThrow()
        assertTrue(repo.createProject("P", "com.a.b").isFailure)
    }

    @Test fun cannotTouchFilesOutsideWorkspace() {
        val outside = File(base, "secret.txt").apply { writeText("s") }
        assertTrue(repo.read(outside).isFailure)
        assertTrue(repo.write(outside, "x").isFailure)
        assertTrue(repo.delete(outside).isFailure)
        assertEquals("s", outside.readText())
        val dir = repo.createProject("P", "com.a.b").getOrThrow()
        assertTrue(repo.read(File(dir, "../../secret.txt")).isFailure)
        assertTrue(repo.rename(File(dir, "settings.gradle.kts"), "../x").isFailure)
    }

    @Test fun symlinkEscapeIsBlocked() {
        val dir = repo.createProject("P", "com.a.b").getOrThrow()
        val secret = File(base, "secret.txt").apply { writeText("s") }
        val link = File(dir, "link.txt")
        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), secret.toPath()) }
            .onFailure { return } // filesystem without symlinks: nothing to test
        assertTrue(repo.read(link).isFailure)
    }

    @Test fun fileOperations() {
        val dir = repo.createProject("P", "com.a.b").getOrThrow()
        val f = repo.createFile(dir, "notes.txt").getOrThrow()
        repo.write(f, "hello").getOrThrow()
        assertEquals("hello", repo.read(f).getOrThrow())
        assertTrue(repo.createFile(dir, "notes.txt").isFailure)
        val renamed = repo.rename(f, "n2.txt").getOrThrow()
        assertFalse(f.exists()); assertEquals("hello", renamed.readText())
        repo.delete(renamed).getOrThrow()
        assertFalse(renamed.exists())
    }

    @Test fun cannotDeleteWorkspaceRootOrNonProjectDir() {
        val dir = repo.createProject("P", "com.a.b").getOrThrow()
        assertTrue(repo.delete(root).isFailure)
        assertTrue(repo.deleteProject(File(dir, "app")).isFailure)
        assertTrue(dir.exists())
        repo.deleteProject(dir).getOrThrow()
        assertFalse(dir.exists())
    }

    @Test fun oversizedFilesAreNotLoaded() {
        val dir = repo.createProject("P", "com.a.b").getOrThrow()
        val big = File(dir, "big.bin")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(ProjectRepository.MAX_EDITABLE_BYTES + 1) }
        assertTrue(repo.read(big).isFailure)
    }

    @Test fun treeListsDirsFirstAndRespectsExpansion() {
        val dir = repo.createProject("P", "com.a.b").getOrThrow()
        val collapsed = repo.tree(dir, emptySet()).map { it.name }
        assertEquals(listOf("app", "settings.gradle.kts"), collapsed)
        val app = File(dir, "app")
        val open = repo.tree(dir, setOf(app.path))
        assertEquals(listOf("app", "src", "build.gradle.kts", "settings.gradle.kts"), open.map { it.name })
        assertEquals(listOf(0, 1, 1, 0), open.map { it.depth })
    }
}

class TemplatesTest {
    @Test fun javaTemplateHasNoKotlinPluginAndAJavaActivity() {
        val files = Templates.create(Language.JAVA, "Demo", "com.example.demo")
        assertTrue(files.keys.any { it.endsWith("MainActivity.java") })
        assertFalse(files.keys.any { it.endsWith(".kt") })
        assertFalse(files.getValue("app/build.gradle.kts").contains("kotlin"))
        assertTrue(files.getValue("app/build.gradle.kts").contains("com.android.application"))
        assertTrue(files.getValue("app/src/main/java/com/example/demo/MainActivity.java").contains("package com.example.demo;"))
    }

    @Test fun kotlinTemplateKeepsKotlin() {
        val files = Templates.create(Language.KOTLIN, "Demo", "com.example.demo")
        assertTrue(files.keys.any { it.endsWith("MainActivity.kt") })
        assertTrue(files.getValue("app/build.gradle.kts").contains("org.jetbrains.kotlin.android"))
    }
}
