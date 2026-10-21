package dev.mobilestudio.build

import java.io.File

sealed interface BuildResult {
    data class Success(val apk: File) : BuildResult
    data class Failure(val reason: String) : BuildResult
}

/** Turns a project directory into an installable APK. */
interface BuildEngine {
    suspend fun build(project: File, log: (String) -> Unit): BuildResult
    fun cancel()
}

/**
 * Runs the standard pipeline: toolchain check, config, manifest, aapt2 compile/link,
 * source compile, dex, package, sign. Compiler and dexer are supplied by the caller.
 */
class PipelineBuildEngine(
    private val toolchain: () -> Toolchain,
    private val workRoot: File,
    private val outputDir: File,
    private val compiler: SourceCompiler,
    private val dexer: Dexer,
    private val signer: ApkSigning,
    private val runner: ProcessRunner = JvmProcessRunner(),
) : BuildEngine {

    @Volatile private var cancelled = false

    override fun cancel() { cancelled = true }

    override suspend fun build(project: File, log: (String) -> Unit): BuildResult {
        cancelled = false
        val ctx = BuildContext(
            project = project,
            workDir = workDirFor(workRoot, project).also { it.mkdirs() },
            toolchain = toolchain(),
            runner = runner,
            log = log,
            isCancelled = { cancelled },
        )
        return BuildPipeline(
            listOf(
                CheckToolchainStep(), ReadConfigStep(), PatchManifestStep(),
                Aapt2CompileStep(), Aapt2LinkStep(),
                CompileSourcesStep(compiler), DexStep(dexer),
                PackageApkStep(), SignApkStep(signer, outputDir),
            )
        ).run(ctx)
    }

    companion object {
        /** Folder names alone can collide (two projects called "App"), so the path is part of the name. */
        fun workDirFor(workRoot: File, project: File): File =
            File(workRoot, "${project.name}-${Integer.toHexString(project.canonicalPath.hashCode())}")
    }
}
