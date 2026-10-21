package dev.mobilestudio.build

import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Compiles Java with the Eclipse compiler (ECJ), which runs on Android where javac does not.
 * android.jar is the boot classpath, so only the Android framework API is visible.
 * Not run here: the ECJ library could not be downloaded in the build environment.
 */
class EcjJavaRunner : JavaRunner {

    override fun compile(req: CompileRequest): StepResult {
        val androidJar = req.classpath.first() // the pipeline always puts android.jar first
        val rest = req.classpath.drop(1)
        val args = buildList {
            add("-1.8"); add("-nowarn"); add("-proc:none")
            add("-bootclasspath"); add(androidJar.path)
            if (rest.isNotEmpty()) { add("-classpath"); add(rest.joinToString(File.pathSeparator) { it.path }) }
            add("-d"); add(req.outDir.path)
            addAll(req.javaSources.map { it.path })
        }
        val out = StringWriter(); val err = StringWriter()
        val ok = BatchCompiler.compile(args.toTypedArray(), PrintWriter(out), PrintWriter(err), null)
        (out.toString() + err.toString()).lines().filter { it.isNotBlank() }.forEach(req.log)
        return if (ok) StepResult.Ok else StepResult.Fail("Java compiler reported errors (see log)")
    }
}
