package dev.mobilestudio.build

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aapt2ArgsTest {
    private val cfg = AppConfig("a.b", "a.b", 26, 35, 35, 4, "1.0")

    @Test fun compileArgs() {
        assertEquals(
            listOf("/t/aapt2", "compile", "--dir", "/p/res", "-o", "/w/c.zip"),
            Aapt2Args.compile(File("/t/aapt2"), File("/p/res"), File("/w/c.zip")),
        )
    }

    @Test fun linkArgsWithResources() {
        val a = Aapt2Args.link(File("/t/aapt2"), File("/t/android.jar"), File("/w/m.xml"), File("/w/c.zip"), File("/w/o.apk"), File("/w/gen"), cfg)
        assertEquals("link", a[1])
        assertEquals("/w/o.apk", a[a.indexOf("-o") + 1])
        assertEquals("/t/android.jar", a[a.indexOf("-I") + 1])
        assertEquals("/w/c.zip", a[a.indexOf("-R") + 1])
        assertEquals("26", a[a.indexOf("--min-sdk-version") + 1])
        assertEquals("4", a[a.indexOf("--version-code") + 1])
    }

    @Test fun linkArgsWithoutResourcesHaveNoR() {
        val a = Aapt2Args.link(File("/t/aapt2"), File("/t/a.jar"), File("/w/m"), null, File("/w/o"), File("/w/g"), cfg)
        assertFalse("-R" in a)
    }

    @Test fun versionNameWithSpacesStaysOneArgument() {
        val a = Aapt2Args.link(File("x"), File("y"), File("m"), null, File("o"), File("g"), cfg.copy(versionName = "1.0 beta"))
        assertEquals("1.0 beta", a[a.indexOf("--version-name") + 1])
    }
}
