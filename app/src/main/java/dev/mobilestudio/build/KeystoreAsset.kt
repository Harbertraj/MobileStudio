package dev.mobilestudio.build

/** The debug keystore ships as base64 text (assets/debug.p12.b64) so that source patches stay free of binary data. */
object KeystoreAsset {
    fun decode(base64Text: ByteArray): ByteArray = java.util.Base64.getMimeDecoder().decode(base64Text)
}
