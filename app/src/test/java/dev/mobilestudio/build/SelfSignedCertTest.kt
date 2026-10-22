package dev.mobilestudio.build

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.Date

class SelfSignedCertTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test fun certificateIsValidAndSelfSigned() {
        val cert = SelfSignedCert.create(pair, "Test Release", "Org")
        cert.checkValidity()
        cert.verify(pair.public) // throws when the signature does not match
        assertTrue(cert.subjectX500Principal.name.contains("CN=Test Release"))
        assertTrue(cert.subjectX500Principal.name.contains("O=Org"))
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertEquals(pair.public, cert.publicKey)
    }

    @Test fun validityIsRoughlyTheRequestedNumberOfYears() {
        val now = Date()
        val cert = SelfSignedCert.create(pair, "x", "y", validYears = 30, now = now)
        val years = (cert.notAfter.time - cert.notBefore.time) / (365.25 * 24 * 3600 * 1000)
        assertEquals(30.0, years, 0.1)
    }

    @Test fun datesFrom2050OnUseGeneralizedTime() {
        // 2026 + 30 = 2056: this is the encoding branch that is easy to get wrong
        val cert = SelfSignedCert.create(pair, "x", "y", validYears = 40)
        assertTrue(cert.notAfter.time > Date().time + 35L * 365 * 24 * 3600 * 1000)
        cert.checkValidity()
    }

    @Test fun serialNumbersDiffer() {
        val a = SelfSignedCert.create(pair, "x", "y").serialNumber
        val b = SelfSignedCert.create(pair, "x", "y").serialNumber
        assertNotEquals(a, b)
        assertTrue(a.signum() > 0)
    }

    @Test fun keyAndCertificateWorkTogetherInAPkcs12Store() {
        val cert = SelfSignedCert.create(pair, "x", "y")
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null); setKeyEntry("k", pair.private, "pw".toCharArray(), arrayOf(cert))
        }
        val bytes = java.io.ByteArrayOutputStream().also { ks.store(it, "pw".toCharArray()) }.toByteArray()
        val key = SigningKey.fromKeystore(bytes.inputStream(), "PKCS12", "pw".toCharArray())
        assertEquals("k", key.alias)
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key.key); update(byteArrayOf(1, 2, 3)) }.sign()
        Signature.getInstance("SHA256withRSA").apply { initVerify(key.chain.first()); update(byteArrayOf(1, 2, 3)) }.also { assertTrue(it.verify(sig)) }
    }
}
