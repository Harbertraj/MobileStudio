package dev.mobilestudio.build

/** Which flavour of APK a build produces. */
enum class BuildVariant(val label: String) {
    /** Fast dex, signed with the shared public debug key. For testing only. */
    DEBUG("debug"),

    /** Optimised dex, signed with a release key (your own from keystore.properties, or one generated on this device). */
    RELEASE("release");

    companion object {
        fun parse(text: String): BuildVariant? = entries.firstOrNull { it.label.equals(text.trim(), ignoreCase = true) }
    }
}
