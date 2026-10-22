package dev.mobilestudio.build

import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** A private key with its certificate chain, ready for apk signing. */
class SigningKey(val alias: String, val key: PrivateKey, val chain: List<X509Certificate>) {

    /** Subject of the signing certificate, for build logs. */
    val subject: String get() = chain.first().subjectX500Principal.name

    companion object {
        /**
         * Reads a key from a keystore stream. When [alias] is null the first key entry is used.
         * [keyPassword] defaults to [storePassword] (the usual case for PKCS12).
         */
        fun fromKeystore(
            input: InputStream,
            storeType: String,
            storePassword: CharArray,
            alias: String? = null,
            keyPassword: CharArray = storePassword,
        ): SigningKey {
            val ks = KeyStore.getInstance(storeType)
            input.use { ks.load(it, storePassword) }
            val name = alias ?: ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
                ?: error("The keystore holds no key")
            val key = ks.getKey(name, keyPassword) as? PrivateKey
                ?: error("Key '$name' not found in the keystore (or the key password is wrong)")
            val chain = ks.getCertificateChain(name)?.map { it as X509Certificate }
                ?: error("Certificate chain for '$name' not found")
            return SigningKey(name, key, chain)
        }
    }
}
