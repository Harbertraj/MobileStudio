package dev.mobilestudio.build

import dev.mobilestudio.data.Templates
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BuildPipelineTest {
    private lateinit var base: File
    private lateinit var project: File
    private val log = mutableListOf<String>()

    @Before fun setUp() {
        base = File.createTempFile("pipe", "").also { it.delete(); it.mkdirs() }
        project = File(base, "Demo").also { it.mkdirs() }
        Templates.emptyActivity("Demo", "com.example.demo").forEach { (path, content) ->
            File(project, path).also { it.parentFile.mkdirs() }.writeText(content)
        }
    }
    @After fun tearDown() { base.deleteRecursively() }

    /** A shell script that behaves like aapt2 well enough for the pipeline: it writes the -o file. */
    private fun fakeAapt2(exitCode: Int = 0): File {
        val f = File(base, "aapt2").apply {
            writeText("""#!/bin/sh
echo "fake aapt2 ${'$'}1"
if [ "${'$'}1" = "compile" ]; then
  while [ "${'$'}1" != "-o" ]; do shift; done; echo c > "${'$'}2"
fi
if [ "${'$'}1" = "link" ]; then
  while [ "${'$'}1" != "-o" ]; do shift; done; cp "$base/seed.apk" "${'$'}2"
fi
exit $exitCode
""")
            setExecutable(true)
        }
        ZipOutputStream(File(base, "seed.apk").outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(1); z.closeEntry()
            z.putNextEntry(ZipEntry("resources.arsc")); z.write(2); z.closeEntry()
        }
        return f
    }

    private fun toolchain(aapt2: File = fakeAapt2()) = Toolchain(aapt2, File(base, "android.jar").apply { writeText("jar") })

    private val okCompiler = object : SourceCompiler {
        override fun compile(ctx: BuildContext, sources: List<File>): StepResult {
            ctx.log("compiling ${sources.size}"); File(ctx.classesDir, "A.class").writeText("x"); return StepResult.Ok
        }
    }
    private val okDexer = object : Dexer {
        override fun dex(ctx: BuildContext): StepResult {
            ctx.dexFiles = listOf(File(ctx.workDir, "classes.dex").apply { writeBytes(byteArrayOf(1, 2)) }); return StepResult.Ok
        }
    }
    private val okSigner = object : ApkSigning {
        override fun sign(unsigned: File, out: File, config: AppConfig): StepResult { unsigned.copyTo(out, overwrite = true); return StepResult.Ok }
    }

    private fun engine(tc: Toolchain, compiler: SourceCompiler = okCompiler, dexer: Dexer = okDexer) =
        PipelineBuildEngine({ tc }, File(base, "work"), File(base, "out"), compiler, dexer, okSigner)

    @Test fun fullPipelineProducesAnApk() = runBlocking {
        val r = engine(toolchain()).build(project) { log += it }
        assertTrue(r.toString(), r is BuildResult.Success)
        val apk = (r as BuildResult.Success).apk
        assertEquals("Demo-debug.apk", apk.name)
        ZipFile(apk).use { z ->
            assertNotNull(z.getEntry("classes.dex")); assertNotNull(z.getEntry("resources.arsc"))
        }
        val manifest = File(PipelineBuildEngine.workDirFor(File(base, "work"), project), "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("package=\"com.example.demo\""))
        assertTrue(log.any { it.contains("fake aapt2 compile") })
        assertTrue(log.any { it.contains("fake aapt2 link") })
    }

    @Test fun userSourceManifestIsNeverModified() = runBlocking<Unit> {
        val manifest = File(project, "app/src/main/AndroidManifest.xml")
        val before = manifest.readText()
        engine(toolchain()).build(project) { }
        assertEquals(before, manifest.readText())
    }

    @Test fun missingToolchainIsReportedPrecisely() = runBlocking {
        val r = engine(Toolchain(File(base, "nope"), File(base, "nope.jar"))).build(project) { }
        r as BuildResult.Failure
        assertTrue(r.reason, r.reason.startsWith("Check toolchain:"))
        assertTrue(r.reason.contains("aapt2 not found")); assertTrue(r.reason.contains("android.jar not found"))
    }

    @Test fun aapt2FailureStopsThePipeline() = runBlocking {
        val r = engine(toolchain(fakeAapt2(exitCode = 3))).build(project) { } as BuildResult.Failure
        assertTrue(r.reason, r.reason.contains("aapt2 compile exited with 3"))
    }

    @Test fun unavailableCompilerGivesClearReason() = runBlocking {
        val r = engine(toolchain(), compiler = UnavailableSourceCompiler).build(project) { } as BuildResult.Failure
        assertTrue(r.reason, r.reason.startsWith("Compile sources: no Kotlin/Java compiler"))
    }

    @Test fun unavailableDexerGivesClearReason() = runBlocking {
        val r = engine(toolchain(), dexer = UnavailableDexer).build(project) { } as BuildResult.Failure
        assertTrue(r.reason, r.reason.startsWith("Dex classes:"))
    }

    @Test fun stepExceptionsBecomeFailures() = runBlocking {
        val boom = object : SourceCompiler { override fun compile(ctx: BuildContext, sources: List<File>): StepResult = throw IllegalStateException("kaboom") }
        val r = engine(toolchain(), compiler = boom).build(project) { } as BuildResult.Failure
        assertTrue(r.reason, r.reason.contains("kaboom"))
    }

    @Test fun missingBuildScriptFails() = runBlocking {
        File(project, "app/build.gradle.kts").delete()
        val r = engine(toolchain()).build(project) { } as BuildResult.Failure
        assertTrue(r.reason, r.reason.contains("no Android module found"))
    }

    @Test fun foldersWithTheModuleAtTheRootAreBuilt() = runBlocking {
        // Single-module layout: build.gradle.kts and src/main directly in the opened folder.
        val flat = File(base, "Flat").also { it.mkdirs() }
        File(project, "app").copyRecursively(flat)
        val r = engine(toolchain()).build(flat) { }
        assertTrue(r.toString(), r is BuildResult.Success)
    }

    @Test fun sameNamedProjectsGetSeparateWorkDirs() {
        val work = File(base, "work")
        val a = PipelineBuildEngine.workDirFor(work, File(base, "x/App"))
        val b = PipelineBuildEngine.workDirFor(work, File(base, "y/App"))
        assertNotEquals(a, b)
        assertEquals(a, PipelineBuildEngine.workDirFor(work, File(base, "x/App")))
    }

    @Test fun kotlinSourcesInSrcMainKotlinAreFound() = runBlocking {
        File(project, "app/src/main/java").deleteRecursively()
        File(project, "app/src/main/kotlin/a").mkdirs()
        File(project, "app/src/main/kotlin/a/A.kt").writeText("package a")
        var seen = emptyList<File>()
        val spy = object : SourceCompiler {
            override fun compile(ctx: BuildContext, sources: List<File>): StepResult {
                seen = sources; File(ctx.classesDir, "A.class").writeText("x"); return StepResult.Ok
            }
        }
        engine(toolchain(), compiler = spy).build(project) { }
        assertEquals(listOf("A.kt"), seen.map { it.name })
    }

    @Test fun cancellationBetweenStepsStops() {
        val ctx = BuildContext(project, File(base, "w"), toolchain(), JvmProcessRunner(), { }, { true })
        val r = BuildPipeline(listOf(CheckToolchainStep())).run(ctx)
        assertEquals(BuildResult.Failure("Cancelled"), r)
    }

    @Test fun processRunnerStreamsOutputAndHonoursCancel() {
        val lines = mutableListOf<String>()
        assertEquals(0, JvmProcessRunner().run(listOf("sh", "-c", "echo a; echo b >&2"), null, { lines += it }, { false }))
        assertEquals(setOf("a", "b"), lines.toSet())
        val t0 = System.currentTimeMillis()
        assertEquals(-1, JvmProcessRunner().run(listOf("sleep", "30"), null, { }, { true }))
        assertTrue(System.currentTimeMillis() - t0 < 5000)
    }

    @Test fun nonExecutableAapt2IsReported() {
        val f = File(base, "aapt2").apply { writeText("x"); setExecutable(false) }
        val tc = Toolchain(f, File(base, "a.jar").apply { writeText("j") })
        assertEquals(listOf("aapt2 is not executable (${f.path})"), tc.problems())
    }
}

class PipelineRobustnessTest {
    @Test fun linkageErrorsAndOutOfMemoryBecomeFailuresInsteadOfCrashing() {
        for (error in listOf<Throwable>(NoClassDefFoundError("org/jetbrains/Missing"), OutOfMemoryError("heap"))) {
            val boom = object : BuildStep {
                override val name = "Heavy tool"
                override fun run(ctx: BuildContext): StepResult = throw error
            }
            val tmp = File.createTempFile("robust", "").also { it.delete(); it.mkdirs() }
            try {
                val ctx = BuildContext(tmp, tmp, Toolchain(File("a"), File("b")), JvmProcessRunner(), { }, { false })
                val r = BuildPipeline(listOf(boom)).run(ctx)
                r as BuildResult.Failure
                assertTrue(r.reason, r.reason.startsWith("Heavy tool: ${error.javaClass.simpleName}"))
            } finally { tmp.deleteRecursively() }
        }
    }
}
