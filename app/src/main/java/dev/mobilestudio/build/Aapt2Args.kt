package dev.mobilestudio.build

import java.io.File

/** Command lines for aapt2. Pure functions so they can be checked without the binary. */
object Aapt2Args {

    fun compile(aapt2: File, resDir: File, outZip: File): List<String> =
        listOf(aapt2.path, "compile", "--dir", resDir.path, "-o", outZip.path)

    fun link(
        aapt2: File,
        androidJar: File,
        manifest: File,
        compiledRes: File?,
        outApk: File,
        genJavaDir: File,
        config: AppConfig,
    ): List<String> = buildList {
        add(aapt2.path); add("link")
        add("-o"); add(outApk.path)
        add("-I"); add(androidJar.path)
        add("--manifest"); add(manifest.path)
        if (compiledRes != null) { add("-R"); add(compiledRes.path) }
        add("--java"); add(genJavaDir.path)
        add("--min-sdk-version"); add(config.minSdk.toString())
        add("--target-sdk-version"); add(config.targetSdk.toString())
        add("--version-code"); add(config.versionCode.toString())
        add("--version-name"); add(config.versionName)
        add("--auto-add-overlay")
    }
}
