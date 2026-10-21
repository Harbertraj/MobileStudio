package dev.mobilestudio.build

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** Checks the real asset shipped in the app, not a copy: it must decode to a usable signing key. */
class DebugKeystoreAssetTest {
    private fun asset(): File = listOf("src/main/assets/debug.p12.b64", "app/src/main/assets/debug.p12.b64")
        .map(::File).firstOrNull { it.isFile } ?: error("debug.p12.b64 not found from ${File(".").canonicalPath}")

    @Test fun assetDecodesToAPkcs12KeystoreWithTheExpectedKey() {
        val bytes = KeystoreAsset.decode(asset().readBytes())
        val ks = KeyStore.getInstance("PKCS12").apply { load(bytes.inputStream(), "android".toCharArray()) }
        val key = ks.getKey("mobilestudio-debug", "android".toCharArray())
        assertTrue(key is PrivateKey)
        assertEquals("RSA", (key as PrivateKey).algorithm)
        val cert = ks.getCertificateChain("mobilestudio-debug").first() as X509Certificate
        cert.checkValidity()
        assertTrue(cert.subjectX500Principal.name.contains("MobileStudio"))
    }
}
