package dev.mobilestudio.build

import org.junit.Assert.*
import org.junit.Test

class GradleConfigTest {
    @Test fun parsesKotlinDsl() {
        val c = GradleConfig.parse("""
            android {
                namespace = "com.example.app"
                compileSdk = 35
                defaultConfig {
                    applicationId = "com.example.real"
                    minSdk = 26
                    targetSdk = 34
                    versionCode = 7
                    versionName = "2.1"
                }
            }
        """.trimIndent()).getOrThrow()
        assertEquals(AppConfig("com.example.app", "com.example.real", 26, 34, 35, 7, "2.1"), c)
    }

    @Test fun parsesGroovyDsl() {
        val c = GradleConfig.parse("""
            android {
                namespace 'com.g.app'
                compileSdkVersion 33
                defaultConfig { minSdkVersion 21; targetSdkVersion 33 }
            }
        """.trimIndent()).getOrThrow()
        assertEquals("com.g.app", c.applicationId)
        assertEquals(21, c.minSdk); assertEquals(33, c.targetSdk); assertEquals(33, c.compileSdk)
        assertEquals(1, c.versionCode); assertEquals("1.0", c.versionName)
    }

    @Test fun commentedLinesAreIgnored() {
        val c = GradleConfig.parse("""
            // namespace = "com.old"
            /* applicationId = "com.block" */
            namespace = "com.new"
            compileSdk = 35
        """.trimIndent()).getOrThrow()
        assertEquals("com.new", c.namespace)
        assertEquals("com.new", c.applicationId)
    }

    @Test fun targetDefaultsToMin() {
        val c = GradleConfig.parse("namespace = \"a.b\"\ncompileSdk = 35\nminSdk = 24").getOrThrow()
        assertEquals(24, c.targetSdk)
    }

    @Test fun missingNamespaceFails() {
        assertTrue(GradleConfig.parse("compileSdk = 35").isFailure)
    }

    @Test fun missingCompileSdkFails() {
        assertTrue(GradleConfig.parse("namespace = \"a.b\"").isFailure)
    }
}
