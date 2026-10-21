package dev.mobilestudio.build

/**
 * Gradle injects package, SDK levels and version info into the manifest at build time;
 * aapt2 expects them in the file. This applies the same values without touching the user's source file.
 */
object ManifestPatcher {

    private val manifestTag = Regex("""<manifest\b[^>]*>""")

    fun patch(manifest: String, config: AppConfig): Result<String> = runCatching {
        val tag = manifestTag.find(manifest) ?: error("AndroidManifest.xml has no <manifest> element")
        require("xmlns:android=" in tag.value) { "<manifest> must declare xmlns:android" }

        var open = tag.value.removeSuffix(">").removeSuffix("/")
        fun addIfMissing(attr: String, value: String) {
            if (!Regex("""\s$attr\s*=""").containsMatchIn(open)) open += " $attr=\"${escape(value)}\""
        }
        addIfMissing("package", config.namespace)
        addIfMissing("android:versionCode", config.versionCode.toString())
        addIfMissing("android:versionName", config.versionName)
        val selfClosing = tag.value.endsWith("/>")
        var patched = manifest.replaceRange(tag.range, if (selfClosing) "$open/>" else "$open>")

        if (!patched.contains("<uses-sdk")) {
            val end = manifestTag.find(patched)!!.range.last + 1
            val sdk = "\n    <uses-sdk android:minSdkVersion=\"${config.minSdk}\" android:targetSdkVersion=\"${config.targetSdk}\" />"
            patched = patched.substring(0, end) + sdk + patched.substring(end)
        }
        patched
    }

    private fun escape(v: String) = v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
}
