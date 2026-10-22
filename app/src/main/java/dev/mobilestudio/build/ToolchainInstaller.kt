package dev.mobilestudio.build

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Opens a URL for reading. A seam so tests do not need the internet. */
fun interface Fetcher {
    @Throws(IOException::class)
    fun open(url: String): InputStream
}

/** Plain HttpURLConnection with redirects followed by hand (GitHub release assets redirect across hosts). */
class HttpFetcher(private val userAgent: String = "MobileStudio") : Fetcher {
    override fun open(url: String): InputStream {
        var current = url
        repeat(10) {
            val conn = URI(current).toURL().openConnection()
            if (conn !is HttpURLConnection) return conn.getInputStream()
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            when {
                code in 300..399 -> {
                    val next = conn.getHeaderField("Location") ?: throw IOException("HTTP $code without Location for $current")
                    conn.disconnect()
                    current = URI(current).resolve(next).toString()
                }
                code == 200 -> return conn.inputStream
                else -> { conn.disconnect(); throw IOException("HTTP $code for $current") }
            }
        }
        throw IOException("Too many redirects for $url")
    }
}

/** Where the installer looks. Everything is overridable; [aapt2Url] pins a specific aapt2 download. */
data class ToolchainSources(
    val mavenBase: String = "https://repo1.maven.org/maven2",
    val sdkRepoBase: String = "https://dl.google.com/android/repository",
    val sdkRepoManifests: List<String> = listOf("repository2-3.xml", "repository2-2.xml", "repository2-1.xml"),
    val githubApi: String = "https://api.github.com",
    /** GitHub repositories whose releases carry Android (aarch64) builds of the SDK build tools. */
    val aapt2Repos: List<String> = listOf("Lzhiyong/sdk-tools"),
    val kotlinVersion: String = "2.0.21",
    val defaultApi: Int = 35,
    val aapt2Url: String? = null,
)

/**
 * Downloads what the build needs so the user does not have to find and import it by hand:
 * kotlin-stdlib (Maven Central), android.jar (the SDK platform package from Google) and an
 * Android build of aapt2 (from a GitHub release, or any URL the user gives).
 *
 * Every piece goes through [ToolchainStore], so the same validation as a manual import applies.
 */
class ToolchainInstaller(
    private val store: ToolchainStore,
    private val fetcher: Fetcher = HttpFetcher(),
    private val sources: () -> ToolchainSources = { ToolchainSources() },
    /** True when aapt2 is already provided some other way (bundled in the APK as libaapt2.so). */
    private val aapt2Provided: () -> Boolean = { false },
    private val expectedMachine: Int? = ElfMachine.ofThisDevice(),
    /** Runs the freshly installed aapt2; returns null when it works, else what went wrong. */
    private val checkAapt2: (File) -> String? = ::runAapt2Version,
) {

    // ---- status ---------------------------------------------------------------------------

    fun hasAapt2(): Boolean = aapt2Provided() || (store.aapt2.isFile && store.aapt2.canExecute())

    fun hasAndroidJar(api: Int?): Boolean =
        store.androidJar.isFile && (api == null || store.androidJarApi == null || store.androidJarApi == api)

    fun hasKotlinStdlib(): Boolean = store.libs().any { it.name.startsWith("kotlin-stdlib") }

    // ---- pieces ---------------------------------------------------------------------------

    fun installKotlinStdlib(log: (String) -> Unit): Result<File> = runCatching {
        val s = sources()
        val name = "kotlin-stdlib-${s.kotlinVersion}.jar"
        val url = "${s.mavenBase}/org/jetbrains/kotlin/kotlin-stdlib/${s.kotlinVersion}/$name"
        log("  downloading $name")
        // Two stdlib versions on the classpath make the dexer fail with duplicate classes.
        store.libs().filter { it.name.startsWith("kotlin-stdlib") && it.name != name }.forEach { store.removeLib(it.name) }
        val file = progress(fetcher.open(url), log).use { store.importLib(it, name).getOrThrow() }
        log("  installed ${file.name}")
        file
    }

    fun installAndroidJar(api: Int, log: (String) -> Unit): Result<File> = runCatching {
        val s = sources()
        val archive = findPlatformUrl(api, s, log)
        log("  downloading Android $api platform (android.jar is inside a ~60 MB package)")
        val file = ZipInputStream(progress(fetcher.open(archive), log)).use { zip ->
            var found: File? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && ANDROID_JAR_ENTRY.matches(entry.name)) {
                    found = store.importAndroidJar(zip).getOrThrow()
                    break
                }
            }
            found ?: error("The platform package did not contain android.jar")
        }
        store.setAndroidJarApi(api)
        log("  installed android.jar (API $api)")
        file
    }

    fun installAapt2(log: (String) -> Unit): Result<File> = runCatching {
        val s = sources()
        val candidates = s.aapt2Url?.let { listOf(it) } ?: discoverAapt2Urls(s, log)
        require(candidates.isNotEmpty()) { NO_AAPT2 }
        val errors = mutableListOf<String>()
        for (url in candidates) {
            log("  downloading aapt2 from $url")
            val attempt = runCatching {
                val file = fetcher.open(url).let { progress(it, log) }.use { importAapt2(it, url) }
                checkAapt2(file)?.let { problem ->
                    store.aapt2.delete()
                    error("downloaded but cannot run on this device: $problem")
                }
                file
            }
            if (attempt.isSuccess) {
                log("  installed aapt2")
                return@runCatching attempt.getOrThrow()
            }
            errors += "$url: ${attempt.exceptionOrNull()?.message}"
            log("  failed: ${attempt.exceptionOrNull()?.message}")
        }
        error("No working aapt2 could be downloaded.\n" + errors.joinToString("\n") + "\n$NO_AAPT2")
    }

    // ---- everything ------------------------------------------------------------------------

    /**
     * Installs what is missing for a project that compiles against [compileSdk].
     * Each piece is tried independently so one failure does not hide the others; all failures are reported together.
     */
    fun ensure(compileSdk: Int?, needsKotlin: Boolean, log: (String) -> Unit): Result<Unit> {
        val api = compileSdk ?: sources().defaultApi
        val errors = mutableListOf<String>()
        if (!hasAapt2()) {
            log("Setting up aapt2")
            installAapt2(log).onFailure { errors += "aapt2: ${it.message}" }
        }
        if (!hasAndroidJar(api)) {
            log("Setting up android.jar")
            installAndroidJar(api, log).onFailure { errors += "android.jar: ${it.message}" }
        }
        if (needsKotlin && !hasKotlinStdlib()) {
            log("Setting up kotlin-stdlib")
            installKotlinStdlib(log).onFailure { errors += "kotlin-stdlib: ${it.message}" }
        }
        return if (errors.isEmpty()) Result.success(Unit)
        else Result.failure(IllegalStateException(errors.joinToString("\n")))
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun findPlatformUrl(api: Int, s: ToolchainSources, log: (String) -> Unit): String {
        for (manifest in s.sdkRepoManifests) {
            val xml = try {
                fetcher.open("${s.sdkRepoBase}/$manifest").use { String(it.readBytes(), Charsets.UTF_8) }
            } catch (e: IOException) {
                log("  could not read $manifest: ${e.message}")
                continue
            }
            findPlatformArchive(xml, api)?.let { return if (it.startsWith("http")) it else "${s.sdkRepoBase}/$it" }
        }
        // The package list was unreachable or did not know this API level: try the usual file names.
        for (rev in listOf("r02", "r01", "r03", "r04")) {
            val url = "${s.sdkRepoBase}/platform-${api}_$rev.zip"
            try {
                fetcher.open(url).close()
                return url
            } catch (_: IOException) { /* try the next revision */ }
        }
        error("Could not find the Android $api platform package. Import android.jar by hand, or check the connection.")
    }

    private fun discoverAapt2Urls(s: ToolchainSources, log: (String) -> Unit): List<String> {
        val urls = mutableListOf<String>()
        for (repo in s.aapt2Repos) {
            try {
                val json = fetcher.open("${s.githubApi}/repos/$repo/releases?per_page=5").use { String(it.readBytes(), Charsets.UTF_8) }
                urls += pickAapt2Assets(json)
            } catch (e: IOException) {
                log("  could not list releases of $repo: ${e.message}")
            }
        }
        return urls.distinct().take(4)
    }

    private fun importAapt2(input: InputStream, url: String): File {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".zip") -> ZipInputStream(input).use { zip ->
                var found: File? = null
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.substringAfterLast('/') == "aapt2") {
                        found = store.importAapt2(zip, expectedMachine).getOrThrow(); break
                    }
                }
                found ?: error("the archive has no aapt2 file")
            }
            path.endsWith(".tar.gz") || path.endsWith(".tgz") -> {
                val tar = GZIPInputStream(input)
                val body = TarReader.find(tar) { it.substringAfterLast('/') == "aapt2" }
                    ?: error("the archive has no aapt2 file")
                store.importAapt2(body.inputStream(), expectedMachine).getOrThrow()
            }
            else -> store.importAapt2(input, expectedMachine).getOrThrow() // a bare binary
        }
    }

    /** Logs a line every ~8 MB so a long download shows it is alive. */
    private fun progress(input: InputStream, log: (String) -> Unit): InputStream = object : FilterInputStream(input) {
        private var total = 0L
        private var next = 8L shl 20
        private fun count(n: Int) {
            if (n <= 0) return
            total += n
            if (total >= next) { log("  ${total shr 20} MB"); next += 8L shl 20 }
        }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { count(it) }
    }

    companion object {
        private val ANDROID_JAR_ENTRY = Regex("""^[^/]+/android\.jar$""")

        const val NO_AAPT2 = "You can point the app at your own build: run 'toolchain aapt2-url <url>' in the terminal " +
            "(a .zip or .tar.gz containing aapt2, or the aapt2 binary itself, built for Android aarch64), " +
            "or import the file in Build toolchain."

        /** The archive file name for `platforms;android-<api>` from the SDK manifest, or null when absent. */
        fun findPlatformArchive(xml: String, api: Int): String? {
            val start = xml.indexOf("path=\"platforms;android-$api\"")
            if (start < 0) return null
            val end = xml.indexOf("</remotePackage>", start).let { if (it < 0) xml.length else it }
            return Regex("<url>([^<]+)</url>").find(xml.substring(start, end))?.groupValues?.get(1)?.trim()
        }

        /** Download URLs of aarch64 archives in a GitHub releases listing, newest first. */
        fun pickAapt2Assets(releasesJson: String): List<String> =
            Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").findAll(releasesJson)
                .map { it.groupValues[1] }
                .filter { url ->
                    val n = url.substringAfterLast('/').lowercase()
                    (n.contains("aarch64") || n.contains("arm64")) &&
                        (n.endsWith(".zip") || n.endsWith(".tar.gz") || n.endsWith(".tgz")) &&
                        listOf("ndk", "clang", "gcc", "llvm").none { n.contains(it) }
                }
                .toList()

        /** Runs `aapt2 version`; null when it starts and exits normally. */
        fun runAapt2Version(aapt2: File): String? = try {
            val p = ProcessBuilder(aapt2.path, "version").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); "aapt2 did not answer" }
            else if (p.exitValue() != 0) out.lineSequence().firstOrNull().orEmpty().ifEmpty { "exit code ${p.exitValue()}" }
            else null
        } catch (e: IOException) {
            e.message ?: "cannot start aapt2"
        }
    }
}

/** Just enough of tar to pull one file out of a (decompressed) archive. */
object TarReader {
    fun find(input: InputStream, wanted: (String) -> Boolean): ByteArray? {
        val header = ByteArray(512)
        while (true) {
            if (!readFully(input, header)) return null
            if (header.all { it == 0.toByte() }) return null // end of archive
            val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
            val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLongOrNull(8) ?: return null
            val type = header[156].toInt().toChar()
            val regular = type == '0' || type == '\u0000'
            val padded = (size + 511) / 512 * 512
            if (regular && wanted(name)) {
                val data = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var left = size
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) return null
                    data.write(buf, 0, n); left -= n
                }
                return data.toByteArray()
            }
            var skip = padded
            while (skip > 0) {
                val n = input.skip(skip)
                if (n <= 0) { if (input.read() < 0) return null else skip-- } else skip -= n
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}
