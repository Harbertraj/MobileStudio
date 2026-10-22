package dev.mobilestudio.build

import dev.mobilestudio.data.Templates
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BuildVariantTest {
    private lateinit var base: File
    private lateinit var project: File
    private val log = mutableListOf<String>()

    @Before fun setUp() {
        base = File.createTempFile("variant", "").also { it.delete(); it.mkdirs() }
        project = File(base, "Demo").also { it.mkdirs() }
        Templates.emptyActivity("Demo", "com.example.demo").forEach { (path, content) ->
            File(project, path).also { it.parentFile.mkdirs() }.writeText(content)
        }
    }
    @After fun tearDown() { base.deleteRecursively() }

    private fun fakeAapt2(): File {
        ZipOutputStream(File(base, "seed.apk").outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(1); z.closeEntry()
            z.putNextEntry(ZipEntry("resources.arsc")); z.write(2); z.closeEntry()
        }
        return File(base, "aapt2").apply {
            writeText("#!/bin/sh\nif [ \"\$1\" = \"compile\" ]; then while [ \"\$1\" != \"-o\" ]; do shift; done; echo c > \"\$2\"; fi\n" +
                "if [ \"\$1\" = \"link\" ]; then while [ \"\$1\" != \"-o\" ]; do shift; done; cp \"$base/seed.apk\" \"\$2\"; fi\n")
            setExecutable(true)
        }
    }

    private val jar get() = File(base, "android.jar").apply { writeText("jar") }
    private fun toolchain() = Toolchain(fakeAapt2(), jar)

    private val compiler = object : SourceCompiler {
        override fun compile(ctx: BuildContext, sources: List<File>): StepResult { File(ctx.classesDir, "A.class").writeText("x"); return StepResult.Ok }
    }
    private val seenVariants = mutableListOf<BuildVariant>()
    private val dexer = object : Dexer {
        override fun dex(ctx: BuildContext): StepResult {
            seenVariants += ctx.variant
            ctx.dexFiles = listOf(File(ctx.workDir, "classes.dex").apply { writeBytes(byteArrayOf(1, 2)) }); return StepResult.Ok
        }
    }
    private val debugSigner = object : ApkSigning {
        override fun sign(unsigned: File, out: File, config: AppConfig): StepResult { unsigned.copyTo(out, overwrite = true); out.appendText("D"); return StepResult.Ok }
    }
    private val releaseSigner = object : ApkSigning {
        override fun sign(unsigned: File, out: File, config: AppConfig): StepResult { unsigned.copyTo(out, overwrite = true); out.appendText("R"); return StepResult.Ok }
    }

    private fun engine(
        tc: () -> Toolchain = { toolchain() },
        release: ((File, (String) -> Unit) -> ApkSigning)? = { _, _ -> releaseSigner },
        setup: ToolchainSetup? = null,
    ) = PipelineBuildEngine(tc, File(base, "work"), File(base, "out"), compiler, dexer, debugSigner, releaseSigner = release, toolchainSetup = setup)

    @Test fun releaseUsesTheReleaseSignerAndNamesTheFileAccordingly() = runBlocking {
        val r = engine().build(project, BuildVariant.RELEASE) { log += it }
        assertTrue(r.toString(), r is BuildResult.Success)
        val apk = (r as BuildResult.Success).apk
        assertEquals("Demo-release.apk", apk.name)
        assertTrue(apk.readBytes().last() == 'R'.code.toByte())
        assertEquals(listOf(BuildVariant.RELEASE), seenVariants)
        assertTrue(log.any { it.contains("release APK") })
    }

    @Test fun debugStillUsesTheDebugSigner() = runBlocking {
        val r = engine().build(project, BuildVariant.DEBUG) { } as BuildResult.Success
        assertEquals("Demo-debug.apk", r.apk.name)
        assertTrue(r.apk.readBytes().last() == 'D'.code.toByte())
    }

    @Test fun debugAndReleaseDoNotShareAWorkDirOrOutput() = runBlocking {
        val e = engine()
        val d = e.build(project, BuildVariant.DEBUG) { } as BuildResult.Success
        val r = e.build(project, BuildVariant.RELEASE) { } as BuildResult.Success
        assertNotEquals(d.apk, r.apk)
        assertTrue(d.apk.isFile && r.apk.isFile)
        assertNotEquals(
            PipelineBuildEngine.workDirFor(File(base, "w"), project, BuildVariant.DEBUG),
            PipelineBuildEngine.workDirFor(File(base, "w"), project, BuildVariant.RELEASE),
        )
    }

    @Test fun releaseWithoutASignerFailsClearly() = runBlocking {
        val r = engine(release = null).build(project, BuildVariant.RELEASE) { } as BuildResult.Failure
        assertTrue(r.reason, r.reason.contains("release signing is not configured"))
    }

    @Test fun aBrokenKeystoreFailsTheSignStepWithItsMessage() = runBlocking {
        val r = engine(release = { _, _ -> error("keystore file not found: /x/y.p12") }).build(project, BuildVariant.RELEASE) { } as BuildResult.Failure
        assertTrue(r.reason, r.reason.startsWith("Sign APK:"))
        assertTrue(r.reason.contains("y.p12"))
    }

    @Test fun releaseSignerReceivesTheProjectFolder() = runBlocking {
        var seen: File? = null
        engine(release = { p, _ -> seen = p; releaseSigner }).build(project, BuildVariant.RELEASE) { }
        assertEquals(project, seen)
    }

    @Test fun parseAcceptsEitherCase() {
        assertEquals(BuildVariant.RELEASE, BuildVariant.parse(" Release "))
        assertEquals(BuildVariant.DEBUG, BuildVariant.parse("debug"))
        assertNull(BuildVariant.parse("staging"))
    }

    // ---- automatic toolchain setup -------------------------------------------------------------------

    @Test fun missingToolsAreFetchedByTheSetupStepThenTheBuildProceeds() = runBlocking {
        val tc = toolchain()
        val aapt2 = tc.aapt2; val androidJar = tc.androidJar
        aapt2.renameTo(File(base, "aapt2.hidden")); androidJar.delete()
        var calls = 0
        var seenConfig: AppConfig? = null
        val setup = ToolchainSetup { config, needsKotlin, log ->
            calls++; seenConfig = config
            log("  fetching")
            File(base, "aapt2.hidden").renameTo(aapt2); androidJar.writeText("jar")
            assertTrue("template is Kotlin", needsKotlin)
            StepResult.Ok
        }
        val r = engine(tc = { Toolchain(aapt2, androidJar) }, setup = setup).build(project) { log += it }
        assertTrue(r.toString(), r is BuildResult.Success)
        assertEquals(1, calls)
        assertEquals("com.example.demo", seenConfig!!.applicationId) // setup gets the parsed project config
        assertTrue(log.indexOf("> Prepare toolchain") < log.indexOf("> Check toolchain"))
    }

    @Test fun aFailedSetupStopsTheBuildWithItsReason() = runBlocking {
        val setup = ToolchainSetup { _, _, _ -> StepResult.Fail("aapt2: HTTP 403") }
        val r = engine(tc = { Toolchain(File(base, "none"), File(base, "none.jar")) }, setup = setup).build(project) { } as BuildResult.Failure
        assertTrue(r.reason, r.reason.startsWith("Prepare toolchain: aapt2: HTTP 403"))
    }

    @Test fun setupIsSkippedWhenTheToolchainIsAlreadyComplete() = runBlocking {
        // A Java project needs no kotlin-stdlib; a complete toolchain must not trigger any download.
        File(project, "app/src/main").walkTopDown().filter { it.extension == "kt" }.toList().forEach { it.delete() }
        File(project, "app/src/main/java/Hello.java").also { it.parentFile.mkdirs() }.writeText("class Hello {}")
        var calls = 0
        val setup = ToolchainSetup { _, _, _ -> calls++; StepResult.Ok }
        val r = engine(setup = setup).build(project) { log += it }
        assertTrue(r.toString(), r is BuildResult.Success)
        assertEquals(0, calls)
        assertTrue(log.any { it.contains("toolchain ready") })
    }

    @Test fun kotlinProjectsAskForTheStdlibWhenOnlyItIsMissing() = runBlocking {
        var needs: Boolean? = null
        val setup = ToolchainSetup { _, needsKotlin, _ -> needs = needsKotlin; StepResult.Ok }
        engine(setup = setup).build(project) { } // template is Kotlin and the test toolchain has no libs
        assertEquals(true, needs)
    }

    @Test fun exportCopiesTheApkIntoTheProject() {
        val apk = File(base, "x/Demo-release.apk").also { it.parentFile.mkdirs(); it.writeText("apk") }
        val out = ApkExport.copyToProject(apk, project, BuildVariant.RELEASE).getOrThrow()
        assertEquals(File(project, "build/outputs/apk/release/Demo-release.apk"), out)
        assertEquals("apk", out.readText())
    }
}
