package dev.mobilestudio.build

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ToolchainStoreTest {
    private lateinit var dir: File
    private lateinit var store: ToolchainStore

    @Before fun setUp() {
        dir = File.createTempFile("tcstore", "").also { it.delete(); it.mkdirs() }
        store = ToolchainStore(File(dir, "toolchain"))
    }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun zip(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> names.forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun aapt2MustBeElfAndBecomesExecutable() {
        assertTrue(store.importAapt2(ByteArrayInputStream("#!/bin/sh".toByteArray())).isFailure)
        assertFalse(store.aapt2.exists())
        val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 2, 3)
        val f = store.importAapt2(ByteArrayInputStream(elf)).getOrThrow()
        assertEquals(store.aapt2, f)
        assertTrue(f.canExecute())
        assertArrayEquals(elf, f.readBytes())
    }

    @Test fun androidJarMustContainActivity() {
        assertTrue(store.importAndroidJar(ByteArrayInputStream(zip("foo/Bar.class"))).isFailure)
        assertTrue(store.importAndroidJar(ByteArrayInputStream("not a zip".toByteArray())).isFailure)
        assertFalse(store.androidJar.exists())
        assertFalse("no temp file left behind", File(store.dir, "android.jar.tmp").exists())
        store.importAndroidJar(ByteArrayInputStream(zip("android/app/Activity.class"))).getOrThrow()
        assertTrue(store.androidJar.isFile)
    }

    @Test fun libsAreValidatedListedAndRemoved() {
        assertTrue(store.importLib(ByteArrayInputStream(zip("a")), "evil.txt").isFailure)
        assertTrue(store.importLib(ByteArrayInputStream("junk".toByteArray()), "bad.jar").isFailure)
        assertTrue(store.libs().isEmpty())
        store.importLib(ByteArrayInputStream(zip("a")), "kotlin-stdlib-2.0.21.jar").getOrThrow()
        store.importLib(ByteArrayInputStream(zip("b")), "another.jar").getOrThrow()
        assertEquals(listOf("another.jar", "kotlin-stdlib-2.0.21.jar"), store.libs().map { it.name })
        assertTrue(store.removeLib("another.jar"))
        assertEquals(1, store.libs().size)
    }

    @Test fun libNamesCannotEscapeTheLibsFolder() {
        val f = store.importLib(ByteArrayInputStream(zip("a")), "../../outside.jar").getOrThrow()
        assertEquals(store.libsDir.canonicalPath, f.parentFile.canonicalPath)
        assertFalse(File(dir, "outside.jar").exists())
    }

    @Test fun reimportReplacesTheOldFile() {
        store.importLib(ByteArrayInputStream(zip("a")), "x.jar").getOrThrow()
        val first = store.libsDir.resolve("x.jar").length()
        store.importLib(ByteArrayInputStream(zip("a", "b", "c")), "x.jar").getOrThrow()
        assertTrue(store.libsDir.resolve("x.jar").length() > first)
    }
}
