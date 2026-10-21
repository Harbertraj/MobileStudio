package dev.mobilestudio.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class FolderBrowserTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = File.createTempFile("browse", "").also { it.delete(); it.mkdirs() }
        listOf("Zeta", "alpha", ".hidden", "Beta").forEach { File(root, it).mkdirs() }
        File(root, "file.txt").writeText("x")
    }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun listsOnlyFoldersSortedIgnoringCaseAndHidesDotFolders() {
        assertEquals(listOf("alpha", "Beta", "Zeta"), FolderBrowser.list(root).getOrThrow().map { it.name })
    }

    @Test fun showHiddenIncludesDotFolders() {
        assertTrue(FolderBrowser.list(root, showHidden = true).getOrThrow().any { it.name == ".hidden" })
    }

    @Test fun listingAFileOrMissingFolderFails() {
        assertTrue(FolderBrowser.list(File(root, "file.txt")).isFailure)
        assertTrue(FolderBrowser.list(File(root, "nope")).isFailure)
    }

    @Test fun parentStopsAtTop() {
        val child = File(root, "alpha")
        assertEquals(root, FolderBrowser.parentOf(child, top = root))
        assertNull(FolderBrowser.parentOf(root, top = root))
        assertNotNull(FolderBrowser.parentOf(root, top = null))
    }

    @Test fun wholeStorageAndFilesystemRootAreTooBroad() {
        assertTrue(FolderBrowser.isTooBroad(root, storageRoot = root))
        assertTrue(FolderBrowser.isTooBroad(File(root, "alpha/.."), storageRoot = root)) // same folder via ..
        assertTrue(FolderBrowser.isTooBroad(File("/"), storageRoot = root))
        assertFalse(FolderBrowser.isTooBroad(File(root, "alpha"), storageRoot = root))
    }

    @Test fun androidProjectDetection() {
        assertFalse(FolderBrowser.looksLikeAndroidProject(root))
        File(root, "app/src/main").mkdirs()
        File(root, "app/src/main/AndroidManifest.xml").writeText("<manifest/>")
        assertFalse("manifest alone is not enough", FolderBrowser.looksLikeAndroidProject(root))
        File(root, "app/build.gradle.kts").writeText("")
        assertTrue(FolderBrowser.looksLikeAndroidProject(root))
    }

    @Test fun moduleLocatorPrefersAppFolderThenRoot() {
        File(root, "src/main").mkdirs(); File(root, "src/main/AndroidManifest.xml").writeText("")
        File(root, "build.gradle").writeText("")
        assertEquals(root, ModuleLocator.find(root))
        File(root, "app/src/main").mkdirs(); File(root, "app/src/main/AndroidManifest.xml").writeText("")
        File(root, "app/build.gradle").writeText("")
        assertEquals(File(root, "app"), ModuleLocator.find(root))
    }
}

class RecentFoldersTest {
    @Test fun newestFirstNoDuplicatesCapped() {
        var l = emptyList<String>()
        l = RecentFolders.add(l, "/a"); l = RecentFolders.add(l, "/b"); l = RecentFolders.add(l, "/a")
        assertEquals(listOf("/a", "/b"), l)
        (1..20).forEach { l = RecentFolders.add(l, "/p$it") }
        assertEquals(RecentFolders.MAX, l.size)
        assertEquals("/p20", l.first())
    }

    @Test fun encodeDecodeRoundTripAndTolerance() {
        val l = listOf("/a b", "/c")
        assertEquals(l, RecentFolders.decode(RecentFolders.encode(l)))
        assertEquals(listOf("/x"), RecentFolders.decode("\n /x \n\n/x\n"))
        assertTrue(RecentFolders.decode(null).isEmpty())
    }

    @Test fun pruneDropsMissingFolders() {
        val tmp = File.createTempFile("recent", "").also { it.delete(); it.mkdirs() }
        try {
            assertEquals(listOf(tmp.path), RecentFolders.prune(listOf("/definitely/not/here", tmp.path)))
        } finally { tmp.deleteRecursively() }
    }
}
