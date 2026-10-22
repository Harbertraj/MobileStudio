package dev.mobilestudio.build

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Creates a self-signed X.509 certificate (RSA, SHA-256) with plain DER encoding.
 * Android has no keytool and no sun.security.x509, and this avoids pulling in a crypto library for one certificate.
 */
object SelfSignedCert {

    private val SHA256_WITH_RSA = oid("1.2.840.113549.1.1.11")
    private val CN = oid("2.5.4.3")
    private val ORG = oid("2.5.4.10")

    fun create(
        keyPair: KeyPair,
        commonName: String,
        organization: String,
        validYears: Int = 30,
        now: Date = Date(),
        random: SecureRandom = SecureRandom(),
    ): X509Certificate {
        val name = sequence(
            set(sequence(CN, utf8(commonName))),
            set(sequence(ORG, utf8(organization))),
        )
        val notAfter = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = now; add(Calendar.YEAR, validYears) }.time
        val serial = BigInteger(63, random).add(BigInteger.ONE) // positive, non-zero
        val algorithm = sequence(SHA256_WITH_RSA, NULL)
        val tbs = sequence(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))), // [0] version v3
            tlv(0x02, serial.toByteArray()),
            algorithm,
            name,
            sequence(time(now), time(notAfter)),
            name,
            keyPair.public.encoded, // SubjectPublicKeyInfo is already DER
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = sequence(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    // ---- DER encoding ----------------------------------------------------------------------

    private val NULL = byteArrayOf(0x05, 0x00)

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n <= 0xFF -> { out.write(0x81); out.write(n) }
            n <= 0xFFFF -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun sequence(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    private fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val body = ByteArrayOutputStream()
        body.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val groups = ArrayList<Int>()
            var v = arc
            do { groups.add(0, (v and 0x7F).toInt()); v = v shr 7 } while (v > 0)
            groups.forEachIndexed { i, g -> body.write(if (i < groups.lastIndex) g or 0x80 else g) }
        }
        return tlv(0x06, body.toByteArray())
    }

    /** UTCTime up to 2049, GeneralizedTime from 2050 (RFC 5280). */
    private fun time(date: Date): ByteArray {
        val year = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }.get(Calendar.YEAR)
        val utc = year < 2050
        val fmt = SimpleDateFormat(if (utc) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return tlv(if (utc) 0x17 else 0x18, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }
}
