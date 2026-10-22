package dev.mobilestudio.build

import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Properties

/**
 * Where the release signing key comes from.
 *
 * 1. `keystore.properties` in the project folder (storeFile, storePassword, keyAlias, keyPassword[, storeType]).
 *    Keep that file out of version control.
 * 2. Otherwise a key generated once on this device and kept in the app's private storage ([dir]).
 *    Updates of an installed app must be signed with the same key, so back it up (the terminal command
 *    `keystore export` copies it next to your project).
 */
class ReleaseKeystore(private val dir: File) {

    val deviceKeystore: File get() = File(dir, "release.p12")
    private val deviceSecret: File get() = File(dir, "release.properties")

    /** Which source [load] would use for [project], without creating anything. */
    fun describe(project: File?): String =
        if (project != null && File(project, PROPERTIES).isFile) "keystore.properties in ${project.name}"
        else if (deviceKeystore.isFile) "release key generated on this device"
        else "a new release key (will be generated on this device)"

    fun load(project: File?, log: (String) -> Unit = {}): SigningKey {
        val props = project?.let { File(it, PROPERTIES) }?.takeIf { it.isFile }
        if (props != null) {
            log("  signing with the key from ${props.name}")
            return fromProperties(project, props)
        }
        if (!deviceKeystore.isFile || !deviceSecret.isFile) {
            log("  generating a release key on this device (once)")
            generate()
        } else {
            log("  signing with the release key generated on this device")
        }
        val password = Properties().apply { deviceSecret.inputStream().use { load(it) } }.getProperty("password")
            ?: error("release.properties has no password")
        return SigningKey.fromKeystore(deviceKeystore.inputStream(), "PKCS12", password.toCharArray(), ALIAS)
    }

    private fun fromProperties(project: File, props: File): SigningKey {
        val p = Properties().apply { props.inputStream().use { load(it) } }
        fun need(key: String) = p.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("$PROPERTIES is missing '$key'")
        val storeName = need("storeFile")
        val storePassword = need("storePassword").toCharArray()
        val storeFile = File(storeName).let { if (it.isAbsolute) it else File(project, it.path) }
        require(storeFile.isFile) { "keystore file not found: ${storeFile.path}" }
        val keyPassword = (p.getProperty("keyPassword")?.takeIf { it.isNotEmpty() } ?: need("storePassword")).toCharArray()
        return SigningKey.fromKeystore(
            storeFile.inputStream(),
            p.getProperty("storeType")?.trim()?.takeIf { it.isNotEmpty() } ?: "PKCS12",
            storePassword,
            p.getProperty("keyAlias")?.trim()?.takeIf { it.isNotEmpty() },
            keyPassword,
        )
    }

    private fun generate() {
        dir.mkdirs()
        val random = SecureRandom()
        val password = ByteArray(18).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val cert = SelfSignedCert.create(pair, commonName = "MobileStudio Release", organization = "MobileStudio", random = random)
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, pair.private, password.toCharArray(), arrayOf(cert))
        }
        val tmp = File(dir, "release.p12.tmp")
        tmp.outputStream().use { ks.store(it, password.toCharArray()) }
        val secret = File(dir, "release.properties.tmp")
        secret.outputStream().use { Properties().apply { setProperty("password", password); setProperty("alias", ALIAS) }.store(it, "MobileStudio release key") }
        deviceKeystore.delete(); deviceSecret.delete()
        check(tmp.renameTo(deviceKeystore) && secret.renameTo(deviceSecret)) { "Could not save the release key" }
    }

    /**
     * Copies the device key and a ready-to-use keystore.properties into [targetDir] so the same key can be used
     * after reinstalling the app. Returns the properties file.
     */
    fun export(targetDir: File): Result<File> = runCatching {
        check(deviceKeystore.isFile && deviceSecret.isFile) { "No device release key exists yet. Run 'build release' once first." }
        targetDir.mkdirs()
        val p12 = File(targetDir, "release.p12")
        deviceKeystore.copyTo(p12, overwrite = true)
        val password = Properties().apply { deviceSecret.inputStream().use { load(it) } }.getProperty("password")
        val out = File(targetDir, "release-keystore.properties")
        out.writeText("storeFile=release.p12\nstorePassword=$password\nkeyAlias=$ALIAS\nkeyPassword=$password\n")
        out
    }

    companion object {
        const val PROPERTIES = "keystore.properties"
        const val ALIAS = "mobilestudio-release"
    }
}
