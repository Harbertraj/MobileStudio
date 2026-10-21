package dev.mobilestudio.build

import org.junit.Assert.*
import org.junit.Test

class ManifestPatcherTest {
    private val config = AppConfig("com.ex.app", "com.ex.app", 26, 35, 35, 3, "1.2")
    private val bare = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="x" />
</manifest>
"""

    @Test fun addsPackageVersionAndSdk() {
        val out = ManifestPatcher.patch(bare, config).getOrThrow()
        assertTrue(out.contains("""package="com.ex.app""""))
        assertTrue(out.contains("""android:versionCode="3""""))
        assertTrue(out.contains("""android:versionName="1.2""""))
        assertTrue(out.contains("""<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />"""))
        assertTrue(out.indexOf("<uses-sdk") < out.indexOf("<application"))
        assertTrue(out.contains("<application android:label=\"x\" />")) // untouched
    }

    @Test fun existingValuesAreKept() {
        val src = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="my.own" android:versionCode="9">
<uses-sdk android:minSdkVersion="19"/>
</manifest>"""
        val out = ManifestPatcher.patch(src, config).getOrThrow()
        assertEquals(1, Regex("package=").findAll(out).count())
        assertTrue(out.contains("package=\"my.own\""))
        assertTrue(out.contains("android:versionCode=\"9\""))
        assertEquals(1, Regex("<uses-sdk").findAll(out).count())
        assertTrue(out.contains("android:versionName=\"1.2\""))
    }

    @Test fun selfClosingManifestKeepsItsSlash() {
        val out = ManifestPatcher.patch("""<manifest xmlns:android="u"/>""", config).getOrThrow()
        assertTrue(out.startsWith("<manifest "))
        assertTrue(out.contains("/>"))
    }

    @Test fun valuesAreEscaped() {
        val out = ManifestPatcher.patch(bare, config.copy(versionName = "a\"b<c&d")).getOrThrow()
        assertTrue(out.contains("""android:versionName="a&quot;b&lt;c&amp;d""""))
    }

    @Test fun rejectsMissingManifestOrNamespace() {
        assertTrue(ManifestPatcher.patch("<application/>", config).isFailure)
        assertTrue(ManifestPatcher.patch("<manifest></manifest>", config).isFailure)
    }
}
