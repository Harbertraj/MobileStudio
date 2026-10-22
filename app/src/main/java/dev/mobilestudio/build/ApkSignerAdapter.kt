package dev.mobilestudio.build

import com.android.apksig.ApkSigner
import java.io.File
import java.io.InputStream

/** Signs APKs with apksig (v1 + v2 + v3) using whichever key [keys] returns. */
class KeyApkSigner(private val keys: () -> SigningKey) : ApkSigning {

    override fun sign(unsigned: File, out: File, config: AppConfig): StepResult {
        val k = keys()
        val signer = ApkSigner.SignerConfig.Builder(k.alias, k.key, k.chain).build()
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

/**
 * Signs with the bundled keystore, which holds a public, shared debug key: APKs signed with it
 * are for testing only and must not be published. Release builds use [ReleaseKeystore] instead.
 */
class ApkSignerAdapter(
    keystore: () -> InputStream,
    alias: String = "mobilestudio-debug",
    password: CharArray = "android".toCharArray(),
) : ApkSigning by KeyApkSigner({ SigningKey.fromKeystore(keystore(), "PKCS12", password, alias) })
