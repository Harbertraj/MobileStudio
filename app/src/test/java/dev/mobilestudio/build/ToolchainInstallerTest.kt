package dev.mobilestudio.build

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ToolchainInstallerTest {
    private lateinit var base: File
    private lateinit var store: ToolchainStore
    private lateinit var server: HttpServer
    private val routes = mutableMapOf<String, ByteArray>()
    private val hits = mutableListOf<String>()
    private val log = mutableListOf<String>()
    private val url get() = "http://127.0.0.1:${server.address.port}"

    @Before fun setUp() {
        base = File.createTempFile("tcinst", "").also { it.delete(); it.mkdirs() }
        store = ToolchainStore(File(base, "toolchain"))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
            hits += path
            when {
                path == "/redirect/jar" -> { ex.responseHeaders.add("Location", "/maven2/redirected.jar"); ex.sendResponseHeaders(302, -1) }
                routes.containsKey(path) -> {
                    val body = routes.getValue(path)
                    ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
                }
                else -> ex.sendResponseHeaders(404, -1)
            }
            ex.close()
        }
        server.start()
    }
    @After fun tearDown() { server.stop(0); base.deleteRecursively() }

    private fun sources(extra: ToolchainSources.() -> ToolchainSources = { this }) = {
        ToolchainSources(
            mavenBase = "$url/maven2", sdkRepoBase = "$url/repo", githubApi = "$url/gh",
            aapt2Repos = listOf("me/tools"),
        ).extra()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun elf(machine: Int) = ByteArray(64).also {
        it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[18] = (machine and 0xFF).toByte(); it[19] = (machine shr 8).toByte()
    }

    private val androidJarBytes by lazy { zip("android/app/Activity.class" to byteArrayOf(1), "AndroidManifest.xml" to byteArrayOf(2)) }

    private fun installer(
        machine: Int? = ElfMachine.AARCH64,
        check: (File) -> String? = { null },
        provided: Boolean = false,
        src: () -> ToolchainSources = sources(),
    ) = ToolchainInstaller(store, HttpFetcher(), src, { provided }, machine, check)

    // ---- kotlin-stdlib -----------------------------------------------------------------------

    @Test fun kotlinStdlibIsDownloadedFromMaven() {
        routes["/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar"] = zip("kotlin/Unit.class" to byteArrayOf(1))
        val f = installer().installKotlinStdlib { log += it }.getOrThrow()
        assertEquals("kotlin-stdlib-2.0.21.jar", f.name)
        assertEquals(listOf("kotlin-stdlib-2.0.21.jar"), store.libs().map { it.name })
    }

    @Test fun anOlderStdlibIsReplacedNotAddedNextTo() {
        store.importLib(zip("a" to byteArrayOf(1)).inputStream(), "kotlin-stdlib-1.9.0.jar").getOrThrow()
        store.importLib(zip("a" to byteArrayOf(1)).inputStream(), "other-lib.jar").getOrThrow()
        routes["/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar"] = zip("kotlin/Unit.class" to byteArrayOf(1))
        installer().installKotlinStdlib { }.getOrThrow()
        assertEquals(listOf("kotlin-stdlib-2.0.21.jar", "other-lib.jar"), store.libs().map { it.name })
    }

    @Test fun anHttpErrorBecomesAFailureNotACrash() {
        val r = installer().installKotlinStdlib { }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("404"))
        assertTrue(store.libs().isEmpty())
    }

    @Test fun anInvalidJarIsRejectedAndNotKept() {
        routes["/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar"] = "<html>blocked</html>".toByteArray()
        assertTrue(installer().installKotlinStdlib { }.isFailure)
        assertTrue(store.libs().isEmpty())
    }

    // ---- android.jar ----------------------------------------------------------------------------

    private fun manifestXml(api: Int, archive: String) = """
        <sdk:sdk-repository xmlns:sdk="x">
          <remotePackage path="platforms;android-${api - 1}"><archives><archive><complete><url>platform-${api - 1}_r01.zip</url></complete></archive></archives></remotePackage>
          <remotePackage path="platforms;android-$api" obsolete="false"><archives><archive><complete><size>1</size><url>$archive</url></complete></archive></archives></remotePackage>
          <remotePackage path="platforms;android-$api-ext4"><archives><archive><complete><url>wrong.zip</url></complete></archive></archives></remotePackage>
        </sdk:sdk-repository>""".trimIndent()

    @Test fun findPlatformArchivePicksTheExactApiLevel() {
        val xml = manifestXml(35, "platform-35_r02.zip")
        assertEquals("platform-35_r02.zip", ToolchainInstaller.findPlatformArchive(xml, 35))
        assertEquals("platform-34_r01.zip", ToolchainInstaller.findPlatformArchive(xml, 34))
        assertNull(ToolchainInstaller.findPlatformArchive(xml, 21))
    }

    @Test fun androidJarIsExtractedFromThePlatformPackage() {
        routes["/repo/repository2-3.xml"] = manifestXml(35, "platform-35_r02.zip").toByteArray()
        routes["/repo/platform-35_r02.zip"] = zip(
            "android-35/data/x.txt" to byteArrayOf(9),
            "android-35/android.jar" to androidJarBytes,
            "android-35/optional/y.jar" to byteArrayOf(8),
        )
        installer().installAndroidJar(35) { log += it }.getOrThrow()
        assertTrue(store.androidJar.isFile)
        assertEquals(35, store.androidJarApi)
        assertTrue(java.util.zip.ZipFile(store.androidJar).use { it.getEntry("android/app/Activity.class") != null })
    }

    @Test fun androidJarFallsBackToGuessedFileNameWhenTheManifestIsMissing() {
        routes["/repo/platform-34_r02.zip"] = zip("android-34/android.jar" to androidJarBytes)
        installer().installAndroidJar(34) { log += it }.getOrThrow()
        assertEquals(34, store.androidJarApi)
        assertTrue(hits.contains("/repo/repository2-3.xml")) // it did try the manifest first
    }

    @Test fun androidJarFailureExplainsItself() {
        val r = installer().installAndroidJar(35) { }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("platform package"))
    }

    @Test fun aDifferentCompileSdkTriggersANewDownload() {
        store.importAndroidJar(androidJarBytes.inputStream()).getOrThrow()
        store.setAndroidJarApi(33)
        val i = installer()
        assertTrue(i.hasAndroidJar(33))
        assertFalse(i.hasAndroidJar(35))
        assertTrue("a hand-imported jar (unknown level) is accepted", run { store.setAndroidJarApi(null); i.hasAndroidJar(35) })
    }

    // ---- aapt2 ----------------------------------------------------------------------------------

    private fun releasesJson(vararg names: String) = "[{\"assets\":[" + names.joinToString(",") {
        "{\"name\":\"$it\",\"browser_download_url\":\"$url/dl/$it\"}"
    } + "]}]"

    @Test fun pickAapt2AssetsKeepsOnlyAarch64Archives() {
        val json = releasesJson("tools-x86_64.zip", "tools-aarch64.zip", "ndk-aarch64.zip", "tools-arm64.tar.gz", "tools-aarch64.sha256")
        val picked = ToolchainInstaller.pickAapt2Assets(json).map { it.substringAfterLast('/') }
        assertEquals(listOf("tools-aarch64.zip", "tools-arm64.tar.gz"), picked)
    }

    @Test fun aapt2IsFoundInAZipFromGithubReleases() {
        routes["/gh/repos/me/tools/releases?per_page=5"] = releasesJson("sdk-tools-aarch64.zip").toByteArray()
        routes["/dl/sdk-tools-aarch64.zip"] = zip("bin/aapt" to elf(ElfMachine.AARCH64), "bin/aapt2" to elf(ElfMachine.AARCH64))
        val f = installer().installAapt2 { log += it }.getOrThrow()
        assertEquals(store.aapt2, f)
        assertTrue(f.canExecute())
    }

    @Test fun aapt2IsFoundInATarGz() {
        routes["/gh/repos/me/tools/releases?per_page=5"] = releasesJson("sdk-tools-aarch64.tar.gz").toByteArray()
        routes["/dl/sdk-tools-aarch64.tar.gz"] = tarGz("tools/aapt" to elf(ElfMachine.AARCH64), "tools/aapt2" to elf(ElfMachine.AARCH64))
        installer().installAapt2 { }.getOrThrow()
        assertTrue(store.aapt2.isFile)
        assertEquals(64, store.aapt2.length())
    }

    @Test fun aUserSuppliedUrlOverridesDiscoveryAndMayBeABareBinary() {
        routes["/mine/aapt2"] = elf(ElfMachine.AARCH64)
        val i = installer(src = sources { copy(aapt2Url = "$url/mine/aapt2") })
        i.installAapt2 { }.getOrThrow()
        assertTrue(store.aapt2.isFile)
        assertTrue("must not query GitHub when a URL is pinned", hits.none { it.startsWith("/gh") })
    }

    @Test fun aBinaryForTheWrongCpuIsRefusedWithAClearMessage() {
        routes["/mine/aapt2"] = elf(ElfMachine.X86_64)
        val r = installer(src = sources { copy(aapt2Url = "$url/mine/aapt2") }).installAapt2 { }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("x86-64"))
        assertTrue(r.exceptionOrNull()!!.message!!.contains("AArch64"))
        assertFalse(store.aapt2.exists())
    }

    @Test fun aBinaryThatDoesNotRunIsRemovedAndReported() {
        routes["/mine/aapt2"] = elf(ElfMachine.AARCH64)
        val r = installer(check = { "library \"libc++_shared.so\" not found" }, src = sources { copy(aapt2Url = "$url/mine/aapt2") }).installAapt2 { }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("libc++_shared.so"))
        assertFalse("a binary that cannot run must not stay behind", store.aapt2.exists())
    }

    @Test fun nextCandidateIsTriedWhenTheFirstFails() {
        routes["/gh/repos/me/tools/releases?per_page=5"] = releasesJson("a-aarch64.zip", "b-aarch64.zip").toByteArray()
        routes["/dl/a-aarch64.zip"] = zip("readme.txt" to byteArrayOf(1)) // no aapt2 inside
        routes["/dl/b-aarch64.zip"] = zip("aapt2" to elf(ElfMachine.AARCH64))
        installer().installAapt2 { log += it }.getOrThrow()
        assertTrue(store.aapt2.isFile)
        assertTrue(log.any { it.contains("failed") })
    }

    @Test fun whenNothingIsFoundTheMessageTellsTheUserWhatToDo() {
        val r = installer().installAapt2 { }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("aapt2-url"))
    }

    @Test fun redirectsAreFollowed() {
        routes["/maven2/redirected.jar"] = zip("a" to byteArrayOf(1))
        val bytes = HttpFetcher().open("$url/redirect/jar").use { it.readBytes() }
        assertEquals(routes["/maven2/redirected.jar"]!!.size, bytes.size)
    }

    // ---- ensure ---------------------------------------------------------------------------------

    @Test fun ensureInstallsOnlyWhatIsMissingAndReportsAllFailuresTogether() {
        store.importAndroidJar(androidJarBytes.inputStream()).getOrThrow()
        store.setAndroidJarApi(35)
        val r = installer().ensure(35, needsKotlin = true) { log += it }
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg.contains("aapt2:")); assertTrue(msg.contains("kotlin-stdlib:"))
        assertFalse("android.jar was present, so it must not be fetched", msg.contains("android.jar:"))
        assertTrue(hits.none { it.startsWith("/repo") })
    }

    @Test fun ensureDoesNothingWhenEverythingIsThere() {
        store.importAndroidJar(androidJarBytes.inputStream()).getOrThrow()
        store.importLib(zip("a" to byteArrayOf(1)).inputStream(), "kotlin-stdlib-2.0.21.jar").getOrThrow()
        store.importAapt2(elf(ElfMachine.AARCH64).inputStream()).getOrThrow()
        assertTrue(installer().ensure(35, needsKotlin = true) { log += it }.isSuccess)
        assertTrue(hits.isEmpty())
    }

    @Test fun aBundledAapt2CountsAsPresent() {
        store.importAndroidJar(androidJarBytes.inputStream()).getOrThrow()
        assertTrue(installer(provided = true).ensure(null, needsKotlin = false) { }.isSuccess)
        assertTrue(hits.isEmpty())
    }

    // ---- tar helper -----------------------------------------------------------------------------

    private fun tarGz(vararg files: Pair<String, ByteArray>): ByteArray {
        val tar = ByteArrayOutputStream()
        for ((name, data) in files) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h)
            String.format("%011o", data.size).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            tar.write(h); tar.write(data)
            tar.write(ByteArray(((data.size + 511) / 512 * 512) - data.size))
        }
        tar.write(ByteArray(1024))
        return ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(tar.toByteArray()) } }.toByteArray()
    }
}
