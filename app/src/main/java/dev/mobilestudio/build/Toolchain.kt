package dev.mobilestudio.build

import java.io.File

/**
 * Files the build needs that cannot be derived from the project.
 *
 * - [aapt2] must be an executable built for the device ABI. On Android 10+ an app may only execute
 *   files from its native library directory, so ship it as jniLibs/<abi>/libaapt2.so.
 * - [androidJar] is android.jar from an SDK platform matching the project's compileSdk.
 * - [libs] are extra jars (for example kotlin-stdlib): put on the compile classpath and dexed into the APK.
 */
class Toolchain(val aapt2: File, val androidJar: File, val libs: List<File> = emptyList()) {

    /** Human readable list of what is absent or unusable; empty when the toolchain is ready. */
    fun problems(): List<String> = buildList {
        if (!aapt2.isFile) add("aapt2 not found at ${aapt2.path}")
        else if (!aapt2.canExecute()) add("aapt2 is not executable (${aapt2.path})")
        if (!androidJar.isFile) add("android.jar not found at ${androidJar.path}")
    }
}
