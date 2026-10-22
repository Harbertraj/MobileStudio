package dev.mobilestudio.build

import com.android.tools.r8.CompilationFailedException
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.io.File

/**
 * Converts class files (and library jars) to dex with D8. Debug builds keep debug info; release builds use D8's optimised mode (no shrinking or obfuscation).
 * Not run here: the R8/D8 library could not be downloaded in the build environment.
 */
class D8Dexer : Dexer {

    override fun dex(ctx: BuildContext): StepResult {
        val config = ctx.config ?: return StepResult.Fail("configuration was not read")
        val classFiles = ctx.classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.map { it.toPath() }.toList()
        if (classFiles.isEmpty()) return StepResult.Fail("no class files to dex")
        val outDir = File(ctx.workDir, "dex").also { it.deleteRecursively(); it.mkdirs() }

        try {
            val command = D8Command.builder()
                .addProgramFiles(classFiles + ctx.toolchain.libs.map { it.toPath() })
                .addLibraryFiles(ctx.toolchain.androidJar.toPath())
                .setMinApiLevel(config.minSdk)
                .setMode(if (ctx.variant == BuildVariant.RELEASE) CompilationMode.RELEASE else CompilationMode.DEBUG)
                .setOutput(outDir.toPath(), OutputMode.DexIndexed)
                .build()
            D8.run(command)
        } catch (e: CompilationFailedException) {
            return StepResult.Fail("d8 failed: ${e.message}")
        }
        ctx.dexFiles = DexOrder.sort(outDir.listFiles()?.toList().orEmpty())
        return StepResult.Ok
    }
}
