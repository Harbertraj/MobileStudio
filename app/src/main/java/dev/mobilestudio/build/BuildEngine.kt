package dev.mobilestudio.build

import java.io.File

sealed interface BuildResult {
    data class Success(val apk: File) : BuildResult
    data class Failure(val reason: String) : BuildResult
}

/** Turns a project directory into an installable APK. */
interface BuildEngine {
    suspend fun build(project: File, variant: BuildVariant, log: (String) -> Unit): BuildResult
    suspend fun build(project: File, log: (String) -> Unit): BuildResult = build(project, BuildVariant.DEBUG, log)
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
    /** Signer for release builds; receives the project folder (it may hold a keystore.properties) and a log. */
    private val releaseSigner: ((project: File, log: (String) -> Unit) -> ApkSigning)? = null,
    /** When set, missing tools are downloaded as a build step instead of failing the build. */
    private val toolchainSetup: ToolchainSetup? = null,
) : BuildEngine {

    @Volatile private var cancelled = false

    override fun cancel() { cancelled = true }

    override suspend fun build(project: File, variant: BuildVariant, log: (String) -> Unit): BuildResult {
        cancelled = false
        val ctx = BuildContext(
            project = project,
            workDir = workDirFor(workRoot, project, variant).also { it.mkdirs() },
            toolchain = toolchain(),
            runner = runner,
            log = log,
            isCancelled = { cancelled },
            variant = variant,
        )
        val signerFor: (BuildContext) -> ApkSigning = { c ->
            if (c.variant == BuildVariant.RELEASE) {
                val make = releaseSigner ?: error("release signing is not configured")
                make(c.project, c.log)
            } else signer
        }
        val setup = toolchainSetup
        val prepare = if (setup == null) listOf(CheckToolchainStep(), ReadConfigStep())
        else listOf(ReadConfigStep(), PrepareToolchainStep(setup, toolchain), CheckToolchainStep())
        log("Building ${variant.label} APK")
        return BuildPipeline(
            prepare + listOf(
                PatchManifestStep(),
                Aapt2CompileStep(), Aapt2LinkStep(),
                CompileSourcesStep(compiler), DexStep(dexer),
                PackageApkStep(), SignApkStep(signerFor, outputDir),
            )
        ).run(ctx)
    }

    companion object {
        /** Folder names alone can collide (two projects called "App"), so the path is part of the name. */
        fun workDirFor(workRoot: File, project: File): File =
            File(workRoot, "${project.name}-${Integer.toHexString(project.canonicalPath.hashCode())}")

        /** Debug and release get separate folders so one never reuses the other's dex output. */
        fun workDirFor(workRoot: File, project: File, variant: BuildVariant): File =
            if (variant == BuildVariant.DEBUG) workDirFor(workRoot, project)
            else File(workDirFor(workRoot, project).path + "-" + variant.label)
    }
}
