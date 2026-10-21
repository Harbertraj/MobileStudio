package dev.mobilestudio.build

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Runs the embedded Kotlin compiler in-process.
 *
 * [useJdk] must stay false on Android: the classpath is android.jar, there is no JDK to read classes from.
 * (It exists so the logic can be exercised on a desktop JVM.) Heavy: the compiler needs a lot of memory,
 * and it has not been run on a real device.
 */
class K2KotlinRunner(private val useJdk: Boolean = false) : KotlinRunner {

    override fun compile(req: CompileRequest): StepResult {
        val args = buildList {
            add("-no-stdlib"); add("-no-reflect")
            if (!useJdk) add("-no-jdk")
            add("-nowarn")
            add("-classpath"); add(req.classpath.joinToString(File.pathSeparator) { it.path })
            add("-d"); add(req.outDir.path)
            addAll(req.kotlinSources.map { it.path })
            addAll(req.javaSources.map { it.path }) // read for symbol resolution only
        }
        val captured = ByteArrayOutputStream()
        val code = PrintStream(captured, true, "UTF-8").use { K2JVMCompiler().exec(it, *args.toTypedArray()) }
        captured.toString("UTF-8").lines().filter { it.isNotBlank() }.forEach(req.log)
        return if (code == ExitCode.OK) StepResult.Ok else StepResult.Fail("Kotlin compiler reported errors (see log)")
    }
}
