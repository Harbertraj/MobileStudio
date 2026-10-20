package dev.mobilestudio.build

import java.io.File

sealed interface BuildResult {
    data object Success : BuildResult
    data class Failure(val reason: String) : BuildResult
}

/**
 * Turns a project directory into an installable APK.
 *
 * A real implementation runs, on-device: aapt2 (compile + link resources), a Kotlin/Java
 * compiler, d8 (dex), then zip-align and apk signing. Those native/JVM tools must be shipped
 * with the app; that is the major remaining piece of work.
 */
interface BuildEngine {
    suspend fun build(project: File, log: (String) -> Unit): BuildResult
}

/**
 * Placeholder engine: checks the project structure and reports what a real build would do.
 * It does not produce an APK.
 */
class ProjectCheckEngine : BuildEngine {

    override suspend fun build(project: File, log: (String) -> Unit): BuildResult {
        log("> Checking project ${project.name}")

        val manifest = File(project, "app/src/main/AndroidManifest.xml")
        if (!manifest.isFile) return BuildResult.Failure("Missing app/src/main/AndroidManifest.xml")
        log("  manifest found")

        val sources = File(project, "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .toList()
        if (sources.isEmpty()) return BuildResult.Failure("No Kotlin or Java sources under app/src/main/java")
        log("  ${sources.size} source file(s)")

        val resources = File(project, "app/src/main/res").walkTopDown().count { it.isFile }
        log("  $resources resource file(s)")

        log("> aapt2 compile/link: not implemented")
        log("> compile Kotlin/Java: not implemented")
        log("> d8 dexing: not implemented")
        log("> sign and package APK: not implemented")
        return BuildResult.Failure("Structure check passed, but the on-device toolchain is not implemented yet")
    }
}
