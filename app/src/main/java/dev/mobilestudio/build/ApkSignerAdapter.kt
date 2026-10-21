package dev.mobilestudio.build

import com.android.apksig.ApkSigner
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Signs APKs with apksig (v1 + v2 + v3). The bundled keystore holds a public, shared debug key,
 * so APKs signed with it are for testing only and must not be published.
 */
class ApkSignerAdapter(
    private val keystore: () -> InputStream,
    private val alias: String = "mobilestudio-debug",
    private val password: CharArray = "android".toCharArray(),
) : ApkSigning {

    override fun sign(unsigned: File, out: File, config: AppConfig): StepResult {
        val ks = KeyStore.getInstance("PKCS12")
        keystore().use { ks.load(it, password) }
        val key = ks.getKey(alias, password) as? PrivateKey ?: return StepResult.Fail("Key '$alias' not found in keystore")
        val chain = ks.getCertificateChain(alias)?.map { it as X509Certificate }
            ?: return StepResult.Fail("Certificate chain for '$alias' not found")

        val signer = ApkSigner.SignerConfig.Builder(alias, key, chain).build()
        out.parentFile?.mkdirs()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsigned)
            .setOutputApk(out)
            .setMinSdkVersion(config.minSdk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        return StepResult.Ok
    }
}
