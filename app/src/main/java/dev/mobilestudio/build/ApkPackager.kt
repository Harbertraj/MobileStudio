package dev.mobilestudio.build

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Builds the unsigned APK: everything aapt2 produced (manifest, resources.arsc, res/) plus dex files.
 *
 * resources.arsc is stored uncompressed and 4-byte aligned, which Android 11+ requires for apps
 * targeting API 30+. Native libraries are not supported (they would need 4096-byte alignment).
 * Sign the result with an apk signer afterwards; signing must come after alignment.
 */
object ApkPackager {

    private const val ALIGNMENT = 4
    private const val FIXED_TIME = 1_000_000_000_000L // keeps output reproducible

    fun pack(resourcesApk: File, dexFiles: List<File>, out: File): Result<Unit> = runCatching {
        require(dexFiles.isNotEmpty()) { "No dex files to package" }
        val names = HashSet<String>()
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        try {
            val counter = CountingStream(tmp.outputStream().buffered())
            ZipOutputStream(counter).use { zip ->
                ZipFile(resourcesApk).use { src ->
                    for (entry in src.entries()) {
                        if (entry.isDirectory) continue
                        require(!entry.name.startsWith("lib/")) { "Native libraries are not supported (${entry.name})" }
                        val bytes = src.getInputStream(entry).use { it.readBytes() }
                        val stored = entry.name == "resources.arsc"
                        add(zip, counter, entry.name, bytes, stored)
                        names += entry.name
                    }
                }
                dexFiles.forEachIndexed { i, dex ->
                    val name = if (i == 0) "classes.dex" else "classes${i + 1}.dex"
                    require(names.add(name)) { "Duplicate entry $name" }
                    add(zip, counter, name, dex.readBytes(), stored = false)
                }
            }
            check(tmp.renameTo(out) || (out.delete() && tmp.renameTo(out))) { "Could not write ${out.name}" }
        } finally {
            tmp.delete()
        }
    }

    private fun add(zip: ZipOutputStream, counter: CountingStream, name: String, bytes: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        e.time = FIXED_TIME
        if (stored) {
            e.method = ZipEntry.STORED
            e.size = bytes.size.toLong()
            e.compressedSize = bytes.size.toLong()
            e.crc = CRC32().also { it.update(bytes) }.value
            val nameLen = name.toByteArray(Charsets.UTF_8).size
            e.extra = padding(counter.count + 30 + nameLen)
        }
        zip.putNextEntry(e)
        zip.write(bytes)
        zip.closeEntry()
    }

    /** Extra field that pushes the entry data to the next [ALIGNMENT] boundary, or null if already aligned. */
    private fun padding(dataOffsetWithoutExtra: Long): ByteArray? {
        val gap = ((ALIGNMENT - dataOffsetWithoutExtra % ALIGNMENT) % ALIGNMENT).toInt()
        if (gap == 0) return null
        val total = gap + ALIGNMENT // an extra field has a 4 byte header, so it cannot be 1-3 bytes long
        val extra = ByteArray(total)
        extra[0] = 0x35; extra[1] = 0xD9.toByte() // 0xD935: the id zipalign uses for padding
        extra[2] = (total - 4).toByte(); extra[3] = 0
        return extra
    }

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}
