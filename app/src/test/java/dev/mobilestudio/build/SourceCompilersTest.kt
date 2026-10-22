package dev.mobilestudio.build

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipOutputStream

/** Uses the real Kotlin compiler and the JDK's javac (standing in for ECJ) on a desktop JVM. */
class SourceCompilersTest {
    private lateinit var base: File
    private lateinit var stdlib: File
    private lateinit var fakeAndroidJar: File
    private val log = mutableListOf<String>()

    /** javac in place of ECJ, so the Kotlin-then-Java ordering can be checked without the ECJ library. */
    private class JavacRunner : JavaRunner {
        override fun compile(req: CompileRequest): StepResult {
            // javax.tools is not on the Android unit-test compile classpath (android.jar), so reach the JDK's javac by reflection.
            val javac = runCatching { Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null) }.getOrNull()
                ?: return StepResult.Fail("no javac")
            val run = Class.forName("javax.tools.Tool").getMethod("run", java.io.InputStream::class.java, java.io.OutputStream::class.java, java.io.OutputStream::class.java, Array<String>::class.java)
            val cp = req.classpath.drop(1).joinToString(File.pathSeparator) { it.path } // drop the placeholder android.jar
            val args = listOf("-nowarn", "-proc:none", "-classpath", cp, "-d", req.outDir.path) + req.javaSources.map { it.path }
            return if (run.invoke(javac, null, null, null, args.toTypedArray()) == 0) StepResult.Ok else StepResult.Fail("javac failed")
        }
    }

    @Before fun setUp() {
        base = File.createTempFile("srccomp", "").also { it.delete(); it.mkdirs() }
        stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
        fakeAndroidJar = File(base, "android.jar").also { ZipOutputStream(it.outputStream()).close() }
    }
    @After fun tearDown() { base.deleteRecursively() }

    private fun ctx(libs: List<File>): BuildContext {
        val c = BuildContext(File(base, "p"), File(base, "w"), Toolchain(File("aapt2"), fakeAndroidJar, libs), JvmProcessRunner(), { log += it }, { false })
        c.classesDir.mkdirs()
        return c
    }

    private fun src(name: String, text: String) = File(base, "src/$name").also { it.parentFile.mkdirs(); it.writeText(text) }

    private val compiler get() = JvmSourceCompiler(K2KotlinRunner(useJdk = true), JavacRunner())

    @Test fun mixedKotlinAndJavaCompileAndRun() {
        val kt = src("Greeter.kt", "package demo\nclass Greeter { fun hi(n: String) = \"Hi \" + n }\nobject Use { fun call(): String = Main.run() }\n")
        val java = src("Main.java", "package demo;\npublic class Main { public static String run() { return new Greeter().hi(\"x\"); } }\n")
        val c = ctx(listOf(stdlib))
        val r = compiler.compile(c, listOf(kt, java))
        assertEquals(log.toString(), StepResult.Ok, r)

        URLClassLoader(arrayOf(c.classesDir.toURI().toURL()), javaClass.classLoader).use { cl ->
            assertEquals("Hi x", cl.loadClass("demo.Main").getMethod("run").invoke(null))
            val use = cl.loadClass("demo.Use").getField("INSTANCE").get(null)
            assertEquals("Hi x", use.javaClass.getMethod("call").invoke(use))
        }
    }

    @Test fun javaOnlyProjectDoesNotNeedKotlinStdlib() {
        val java = src("Main.java", "public class Main { public static int one() { return 1; } }\n")
        assertEquals(StepResult.Ok, compiler.compile(ctx(emptyList()), listOf(java)))
    }

    @Test fun kotlinWithoutStdlibExplainsWhatToImport() {
        val r = compiler.compile(ctx(emptyList()), listOf(src("A.kt", "class A")))
        r as StepResult.Fail
        assertTrue(r.reason, r.reason.contains("kotlin-stdlib"))
    }

    @Test fun kotlinErrorsFailAndAreLogged() {
        val r = compiler.compile(ctx(listOf(stdlib)), listOf(src("Bad.kt", "class Bad { val x: Int = \"no\" }")))
        assertTrue(r is StepResult.Fail)
        assertTrue(log.toString(), log.any { it.contains("Bad.kt") })
    }

    @Test fun javaErrorsFail() {
        val r = compiler.compile(ctx(emptyList()), listOf(src("Bad.java", "public class Bad { int x = \"no\"; }")))
        assertTrue(r is StepResult.Fail)
    }

    @Test fun noOutputIsAFailureNotASuccess() {
        val noop = object : JavaRunner { override fun compile(req: CompileRequest) = StepResult.Ok }
        val r = JvmSourceCompiler(K2KotlinRunner(useJdk = true), noop).compile(ctx(emptyList()), listOf(src("A.java", "class A {}")))
        r as StepResult.Fail
        assertTrue(r.reason.contains("no class files"))
    }

    @Test fun dexFilesAreOrderedAsAndroidExpects() {
        val names = listOf("classes10.dex", "classes2.dex", "classes.dex", "notes.txt", "classes3.dex")
        assertEquals(
            listOf("classes.dex", "classes2.dex", "classes3.dex", "classes10.dex"),
            DexOrder.sort(names.map { File(it) }).map { it.name },
        )
    }
}
