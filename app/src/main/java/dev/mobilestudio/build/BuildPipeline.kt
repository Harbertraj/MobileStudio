package dev.mobilestudio.build

import java.io.File

sealed interface StepResult {
    data object Ok : StepResult
    data class Fail(val reason: String) : StepResult
}

/** Shared state handed from step to step. Each step reads what earlier steps produced. */
class BuildContext(
    val project: File,
    val workDir: File,
    val toolchain: Toolchain,
    val runner: ProcessRunner,
    val log: (String) -> Unit,
    val isCancelled: () -> Boolean,
) {
    var config: AppConfig? = null
    var manifest: File? = null
    var compiledRes: File? = null
    var resourcesApk: File? = null
    var genJavaDir: File = File(workDir, "gen")
    var classesDir: File = File(workDir, "classes")
    var dexFiles: List<File> = emptyList()
    var unsignedApk: File? = null
    var signedApk: File? = null

    /** The Android module folder (`<project>/app`, or the project itself); set by the first step that reads the config. */
    var moduleDir: File? = null
    val mainDir: File get() = File(checkNotNull(moduleDir) { "module was not located" }, "src/main")
    val classpath: List<File> get() = listOf(toolchain.androidJar) + toolchain.libs
}

interface BuildStep {
    val name: String
    fun run(ctx: BuildContext): StepResult
}

/** Compiles Kotlin/Java sources (and generated R) into class files under ctx.classesDir. */
interface SourceCompiler { fun compile(ctx: BuildContext, sources: List<File>): StepResult }

/** Converts class files under ctx.classesDir into dex files; must set ctx.dexFiles. */
interface Dexer { fun dex(ctx: BuildContext): StepResult }

/** Signs ctx.unsignedApk into [out]. */
interface ApkSigning { fun sign(unsigned: File, out: File, config: AppConfig): StepResult }

class BuildPipeline(private val steps: List<BuildStep>) {

    fun run(ctx: BuildContext): BuildResult {
        for (step in steps) {
            if (ctx.isCancelled()) return BuildResult.Failure("Cancelled")
            ctx.log("> ${step.name}")
            val result = try {
                step.run(ctx)
            } catch (e: Throwable) {
                StepResult.Fail("${e.javaClass.simpleName}: ${e.message}")
            }
            if (result is StepResult.Fail) return BuildResult.Failure("${step.name}: ${result.reason}")
        }
        val apk = ctx.signedApk ?: return BuildResult.Failure("Pipeline finished without producing an APK")
        return BuildResult.Success(apk)
    }
}
