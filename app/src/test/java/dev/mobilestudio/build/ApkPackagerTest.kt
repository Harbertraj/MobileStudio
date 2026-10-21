package dev.mobilestudio.build

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkPackagerTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = File.createTempFile("apkpack", "").also { it.delete(); it.mkdirs() } }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun resourcesApk(vararg entries: Pair<String, ByteArray>): File {
        val f = File(dir, "res.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, data) in entries) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
        }
        return f
    }

    /** Offset of each entry's data, read straight from the central directory and local headers. */
    private fun dataOffsets(apk: File): Map<String, Long> {
        val b = ByteBuffer.wrap(apk.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = b.limit() - 22
        while (b.getInt(eocd) != 0x06054b50) eocd--
        val count = b.getShort(eocd + 10).toInt()
        var p = b.getInt(eocd + 16)
        val out = LinkedHashMap<String, Long>()
        repeat(count) {
            val nameLen = b.getShort(p + 28).toInt(); val extraLen = b.getShort(p + 30).toInt(); val commentLen = b.getShort(p + 32).toInt()
            val local = b.getInt(p + 42)
            val name = String(b.array(), p + 46, nameLen, Charsets.UTF_8)
            val lNameLen = b.getShort(local + 26).toInt(); val lExtraLen = b.getShort(local + 28).toInt()
            out[name] = (local + 30 + lNameLen + lExtraLen).toLong()
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    @Test fun packsResourcesAndDexWithAlignedStoredArsc() {
        for (nameLen in 1..9) { // varies the header size so every alignment case is hit
            val res = resourcesApk(
                "AndroidManifest.xml" to "m".repeat(nameLen).toByteArray(),
                "res/layout/${"x".repeat(nameLen)}.xml" to ByteArray(50) { 1 },
                "resources.arsc" to ByteArray(1000) { (it % 7).toByte() },
            )
            val dex = File(dir, "classes.dex").apply { writeBytes(byteArrayOf(0x64, 0x65, 0x78, 0x0a)) }
            val out = File(dir, "out$nameLen.apk")
            ApkPackager.pack(res, listOf(dex), out).getOrThrow()

            ZipFile(out).use { z ->
                assertEquals(
                    setOf("AndroidManifest.xml", "res/layout/${"x".repeat(nameLen)}.xml", "resources.arsc", "classes.dex"),
                    z.entries().asSequence().map { it.name }.toSet(),
                )
                assertEquals(ZipEntry.STORED, z.getEntry("resources.arsc").method)
                assertEquals(ZipEntry.DEFLATED, z.getEntry("classes.dex").method)
                assertArrayEquals(ByteArray(1000) { (it % 7).toByte() }, z.getInputStream(z.getEntry("resources.arsc")).readBytes())
                assertEquals(4, z.getInputStream(z.getEntry("classes.dex")).readBytes().size)
            }
            assertEquals("nameLen=$nameLen", 0L, dataOffsets(out).getValue("resources.arsc") % 4)
        }
    }

    @Test fun multipleDexFilesAreNumbered() {
        val res = resourcesApk("AndroidManifest.xml" to byteArrayOf(1))
        val d1 = File(dir, "a").apply { writeBytes(byteArrayOf(1)) }
        val d2 = File(dir, "b").apply { writeBytes(byteArrayOf(2)) }
        val out = File(dir, "o.apk")
        ApkPackager.pack(res, listOf(d1, d2), out).getOrThrow()
        ZipFile(out).use { z ->
            assertNotNull(z.getEntry("classes.dex")); assertNotNull(z.getEntry("classes2.dex"))
        }
    }

    @Test fun outputIsReproducible() {
        val res = resourcesApk("AndroidManifest.xml" to byteArrayOf(1), "resources.arsc" to byteArrayOf(2, 3))
        val dex = File(dir, "d").apply { writeBytes(byteArrayOf(9)) }
        val a = File(dir, "a.apk"); val b = File(dir, "b.apk")
        ApkPackager.pack(res, listOf(dex), a).getOrThrow()
        ApkPackager.pack(res, listOf(dex), b).getOrThrow()
        assertArrayEquals(a.readBytes(), b.readBytes())
    }

    @Test fun rejectsNoDexAndNativeLibs() {
        val res = resourcesApk("AndroidManifest.xml" to byteArrayOf(1))
        assertTrue(ApkPackager.pack(res, emptyList(), File(dir, "o.apk")).isFailure)
        val withLib = resourcesApk("lib/arm64-v8a/x.so" to byteArrayOf(1))
        val dex = File(dir, "d").apply { writeBytes(byteArrayOf(9)) }
        assertTrue(ApkPackager.pack(withLib, listOf(dex), File(dir, "o2.apk")).isFailure)
        assertFalse(File(dir, "o2.apk").exists())
        assertFalse(File(dir, "o2.apk.tmp").exists())
    }
}
