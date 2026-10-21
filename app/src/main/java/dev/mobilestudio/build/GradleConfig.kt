package dev.mobilestudio.build

data class AppConfig(
    val namespace: String,
    val applicationId: String,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int,
    val versionCode: Int,
    val versionName: String,
)

/**
 * Reads the few android{} values the builder needs from app/build.gradle(.kts).
 * This is a regex reader, not a Gradle evaluator: values computed in code are not understood.
 */
object GradleConfig {

    fun parse(script: String): Result<AppConfig> = runCatching {
        val text = stripComments(script)
        fun str(key: String) = Regex("""\b$key\s*=?\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
        fun int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
            Regex("""\b$key\s*=?\s*(\d+)\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        }

        val namespace = str("namespace")
        val appId = str("applicationId")
        val base = namespace ?: appId ?: error("build.gradle has neither namespace nor applicationId")
        val minSdk = int("minSdk", "minSdkVersion") ?: 1
        AppConfig(
            namespace = namespace ?: base,
            applicationId = appId ?: base,
            minSdk = minSdk,
            targetSdk = int("targetSdk", "targetSdkVersion") ?: minSdk,
            compileSdk = int("compileSdk", "compileSdkVersion") ?: error("compileSdk is not set"),
            versionCode = int("versionCode") ?: 1,
            versionName = str("versionName") ?: "1.0",
        )
    }

    private fun stripComments(s: String): String =
        s.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines().joinToString("\n") { line -> if (line.trimStart().startsWith("//")) "" else line }
}
