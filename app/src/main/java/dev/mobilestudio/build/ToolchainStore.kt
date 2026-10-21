package dev.mobilestudio.build

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Where user-supplied build tools live inside the app's private storage, and how they are imported.
 * Imports are validated so a wrong file is rejected with a clear message instead of failing later in the build.
 */
class ToolchainStore(val dir: File) {

    val aapt2: File get() = File(dir, "aapt2")
    val androidJar: File get() = File(dir, "android.jar")
    val libsDir: File get() = File(dir, "libs")

    /** Extra jars: added to the compile classpath and dexed into the APK (e.g. kotlin-stdlib). */
    fun libs(): List<File> =
        libsDir.listFiles { f -> f.isFile && f.extension == "jar" }?.sortedBy { it.name } ?: emptyList()

    fun importAapt2(input: InputStream): Result<File> = runCatching {
        val bytes = input.use { it.readBytes() }
        require(bytes.size >= 4 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            "That file is not an ELF executable. Pick the aapt2 binary built for Android (aarch64)."
        }
        dir.mkdirs()
        val tmp = File(dir, "aapt2.tmp").also { it.writeBytes(bytes) }
        check(tmp.setExecutable(true, false)) { "Could not mark aapt2 executable" }
        replace(tmp, aapt2)
    }

    fun importAndroidJar(input: InputStream): Result<File> = runCatching {
        val tmp = copyToTemp(input, "android.jar.tmp")
        try {
            ZipFile(tmp).use { z ->
                require(z.getEntry("android/app/Activity.class") != null) {
                    "That jar does not contain android/app/Activity.class. Pick android.jar from an SDK platform."
                }
            }
        } catch (e: Exception) {
            tmp.delete(); throw e
        }
        replace(tmp, androidJar)
    }

    fun importLib(input: InputStream, fileName: String): Result<File> = runCatching {
        val name = File(fileName).name
        require(name.endsWith(".jar") && !name.startsWith(".")) { "Only .jar files can be imported (got '$fileName')" }
        val tmp = copyToTemp(input, "lib.tmp")
        try {
            ZipFile(tmp).close() // throws if it is not a zip
        } catch (e: Exception) {
            tmp.delete(); throw IllegalArgumentException("'$name' is not a valid jar")
        }
        libsDir.mkdirs()
        replace(tmp, File(libsDir, name))
    }

    fun removeLib(name: String): Boolean = File(libsDir, File(name).name).delete()

    private fun copyToTemp(input: InputStream, tmpName: String): File {
        dir.mkdirs()
        val tmp = File(dir, tmpName)
        input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
        return tmp
    }

    private fun replace(tmp: File, target: File): File {
        target.delete()
        check(tmp.renameTo(target)) { "Could not write ${target.name}" }
        return target
    }
}
