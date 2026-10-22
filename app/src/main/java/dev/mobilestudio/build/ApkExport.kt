package dev.mobilestudio.build

import java.io.File

/** Copies a finished APK to `<project>/build/outputs/apk/<variant>/` so it can be found, shared or committed from the file manager. */
object ApkExport {
    fun copyToProject(apk: File, project: File, variant: BuildVariant): Result<File> = runCatching {
        val dir = File(project, "build/outputs/apk/${variant.label}")
        check(dir.isDirectory || dir.mkdirs()) { "Could not create ${dir.path}" }
        apk.copyTo(File(dir, apk.name), overwrite = true)
    }
}
