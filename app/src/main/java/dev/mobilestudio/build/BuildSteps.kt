package dev.mobilestudio.build

import dev.mobilestudio.data.ModuleLocator
import java.io.File

private fun fail(reason: String) = StepResult.Fail(reason)

class CheckToolchainStep : BuildStep {
    override val name = "Check toolchain"
    override fun run(ctx: BuildContext): StepResult {
        val problems = ctx.toolchain.problems()
        return if (problems.isEmpty()) StepResult.Ok else fail(problems.joinToString("; "))
    }
}

/** Downloads whatever tools are missing (aapt2, android.jar, kotlin-stdlib) before the build needs them. */
fun interface ToolchainSetup {
    fun ensure(config: AppConfig, needsKotlin: Boolean, log: (String) -> Unit): StepResult
}

class PrepareToolchainStep(
    private val setup: ToolchainSetup,
    private val refresh: () -> Toolchain,
) : BuildStep {
    override val name = "Prepare toolchain"
    override fun run(ctx: BuildContext): StepResult {
        val config = ctx.config ?: return fail("configuration was not read")
        if (ctx.toolchain.problems().isEmpty() && !needsKotlinStdlib(ctx)) {
            ctx.log("  toolchain ready")
            return StepResult.Ok
        }
        val result = setup.ensure(config, hasKotlinSources(ctx), ctx.log)
        // Files were added behind the toolchain's back: look again.
        ctx.toolchain = refresh()
        return result
    }

    private fun needsKotlinStdlib(ctx: BuildContext) =
        hasKotlinSources(ctx) && ctx.toolchain.libs.none { it.name.startsWith("kotlin-stdlib") }

    private fun hasKotlinSources(ctx: BuildContext): Boolean =
        listOf("java", "kotlin").any { d -> ctx.mainDir.resolve(d).walkTopDown().any { it.isFile && it.extension == "kt" } }
}

class ReadConfigStep : BuildStep {
    override val name = "Read project configuration"
    override fun run(ctx: BuildContext): StepResult {
        val module = ModuleLocator.find(ctx.project)
            ?: return fail("no Android module found (expected app/build.gradle(.kts) and app/src/main/AndroidManifest.xml)")
        ctx.moduleDir = module
        val script = listOf("build.gradle.kts", "build.gradle").map { File(module, it) }.first { it.isFile }
        val config = GradleConfig.parse(script.readText()).getOrElse { return fail(it.message ?: "invalid build script") }
        ctx.config = config
        ctx.log("  ${config.applicationId} (minSdk ${config.minSdk}, targetSdk ${config.targetSdk}, compileSdk ${config.compileSdk})")
        return StepResult.Ok
    }
}

class PatchManifestStep : BuildStep {
    override val name = "Prepare manifest"
    override fun run(ctx: BuildContext): StepResult {
        val config = ctx.config ?: return fail("configuration was not read")
        val source = File(ctx.mainDir, "AndroidManifest.xml")
        if (!source.isFile) return fail("AndroidManifest.xml not found in ${ctx.mainDir.path}")
        val patched = ManifestPatcher.patch(source.readText(), config).getOrElse { return fail(it.message ?: "bad manifest") }
        val out = File(ctx.workDir, "AndroidManifest.xml")
        out.parentFile.mkdirs()
        out.writeText(patched)
        ctx.manifest = out
        return StepResult.Ok
    }
}

class Aapt2CompileStep : BuildStep {
    override val name = "Compile resources (aapt2)"
    override fun run(ctx: BuildContext): StepResult {
        val res = File(ctx.mainDir, "res")
        if (!res.isDirectory || res.walkTopDown().none { it.isFile }) {
            ctx.log("  no resources")
            return StepResult.Ok
        }
        val out = File(ctx.workDir, "compiled_res.zip").also { it.parentFile.mkdirs(); it.delete() }
        val code = ctx.runner.run(Aapt2Args.compile(ctx.toolchain.aapt2, res, out), ctx.workDir, ctx.log, ctx.isCancelled)
        if (code != 0) return fail("aapt2 compile exited with $code")
        ctx.compiledRes = out
        return StepResult.Ok
    }
}

class Aapt2LinkStep : BuildStep {
    override val name = "Link resources (aapt2)"
    override fun run(ctx: BuildContext): StepResult {
        val config = ctx.config ?: return fail("configuration was not read")
        val manifest = ctx.manifest ?: return fail("manifest was not prepared")
        val out = File(ctx.workDir, "resources.apk").also { it.delete() }
        ctx.genJavaDir.deleteRecursively(); ctx.genJavaDir.mkdirs()
        val args = Aapt2Args.link(ctx.toolchain.aapt2, ctx.toolchain.androidJar, manifest, ctx.compiledRes, out, ctx.genJavaDir, config)
        val code = ctx.runner.run(args, ctx.workDir, ctx.log, ctx.isCancelled)
        if (code != 0) return fail("aapt2 link exited with $code")
        if (!out.isFile) return fail("aapt2 did not produce resources.apk")
        ctx.resourcesApk = out
        return StepResult.Ok
    }
}

class CompileSourcesStep(private val compiler: SourceCompiler) : BuildStep {
    override val name = "Compile sources"
    override fun run(ctx: BuildContext): StepResult {
        val sources = listOf("java", "kotlin")
            .flatMap { ctx.mainDir.resolve(it).walkTopDown().filter { f -> f.isFile && f.extension in setOf("kt", "java") }.toList() }
            .distinct().toMutableList()
        sources += ctx.genJavaDir.walkTopDown().filter { it.isFile && it.extension == "java" }
        if (sources.isEmpty()) return fail("no Kotlin or Java source files under src/main/java or src/main/kotlin")
        ctx.classesDir.deleteRecursively(); ctx.classesDir.mkdirs()
        return compiler.compile(ctx, sources)
    }
}

class DexStep(private val dexer: Dexer) : BuildStep {
    override val name = "Dex classes"
    override fun run(ctx: BuildContext): StepResult {
        val result = dexer.dex(ctx)
        if (result is StepResult.Fail) return result
        return if (ctx.dexFiles.isEmpty()) fail("dexer produced no output") else StepResult.Ok
    }
}

class PackageApkStep : BuildStep {
    override val name = "Package APK"
    override fun run(ctx: BuildContext): StepResult {
        val res = ctx.resourcesApk ?: return fail("resources were not linked")
        val out = File(ctx.workDir, "unsigned.apk")
        ApkPackager.pack(res, ctx.dexFiles, out).getOrElse { return fail(it.message ?: "packaging failed") }
        ctx.unsignedApk = out
        return StepResult.Ok
    }
}

class SignApkStep(private val signerFor: (BuildContext) -> ApkSigning, private val outputDir: File) : BuildStep {
    constructor(signer: ApkSigning, outputDir: File) : this({ _ -> signer }, outputDir)

    override val name = "Sign APK"
    override fun run(ctx: BuildContext): StepResult {
        val unsigned = ctx.unsignedApk ?: return fail("APK was not packaged")
        val config = ctx.config ?: return fail("configuration was not read")
        outputDir.mkdirs()
        val out = File(outputDir, "${ctx.project.name}-${ctx.variant.label}.apk")
        val signer = try { signerFor(ctx) } catch (e: Exception) { return fail(e.message ?: "signing is not available") }
        val result = try { signer.sign(unsigned, out, config) } catch (e: Exception) { return fail("${e.javaClass.simpleName}: ${e.message}") }
        if (result is StepResult.Fail) return result
        ctx.signedApk = out
        return StepResult.Ok
    }
}

/** Stand-in for tools this app does not bundle yet; fails with a precise reason instead of a vague one. */
object UnavailableSourceCompiler : SourceCompiler {
    override fun compile(ctx: BuildContext, sources: List<File>) =
        fail("no Kotlin/Java compiler is bundled yet (${sources.size} source file(s) were ready to compile)")
}

object UnavailableDexer : Dexer {
    override fun dex(ctx: BuildContext) = fail("no dexer (d8) is bundled yet")
}
