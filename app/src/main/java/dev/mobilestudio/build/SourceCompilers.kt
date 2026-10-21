package dev.mobilestudio.build

import java.io.File

class CompileRequest(
    val kotlinSources: List<File>,
    val javaSources: List<File>,
    /** android.jar plus library jars. Class files already produced are added by the caller where needed. */
    val classpath: List<File>,
    val outDir: File,
    val log: (String) -> Unit,
)

interface KotlinRunner { fun compile(req: CompileRequest): StepResult }
interface JavaRunner { fun compile(req: CompileRequest): StepResult }

/**
 * Compiles a mixed Kotlin/Java source set the way Gradle does: Kotlin first (it reads the Java sources
 * to resolve references), then Java against the Kotlin output.
 */
class JvmSourceCompiler(private val kotlin: KotlinRunner, private val java: JavaRunner) : SourceCompiler {

    override fun compile(ctx: BuildContext, sources: List<File>): StepResult {
        val kt = sources.filter { it.extension == "kt" }
        val java = sources.filter { it.extension == "java" }
        val classpath = ctx.classpath

        if (kt.isNotEmpty() && ctx.toolchain.libs.none { it.name.startsWith("kotlin-stdlib") }) {
            return StepResult.Fail("Kotlin sources need kotlin-stdlib-*.jar in the toolchain. Import it in Toolchain settings.")
        }
        val outDir = ctx.classesDir
        ctx.log("  ${kt.size} Kotlin file(s), ${java.size} Java file(s)")

        if (kt.isNotEmpty()) {
            val r = this.kotlin.compile(CompileRequest(kt, java, classpath, outDir, ctx.log))
            if (r is StepResult.Fail) return r
        }
        if (java.isNotEmpty()) {
            // Java sees the Kotlin classes that were just produced.
            val r = this.java.compile(CompileRequest(emptyList(), java, classpath + outDir, outDir, ctx.log))
            if (r is StepResult.Fail) return r
        }
        val produced = outDir.walkTopDown().count { it.isFile && it.extension == "class" }
        return if (produced == 0) StepResult.Fail("compilation produced no class files") else StepResult.Ok
    }
}

/** Orders dex output as Android expects: classes.dex, classes2.dex, classes3.dex, ... */
object DexOrder {
    fun sort(files: List<File>): List<File> = files
        .filter { it.extension == "dex" }
        .sortedBy { f ->
            val n = f.nameWithoutExtension.removePrefix("classes")
            if (n.isEmpty()) 1 else n.toIntOrNull() ?: Int.MAX_VALUE
        }
}
