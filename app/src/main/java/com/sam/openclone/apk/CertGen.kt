package com.sam.openclone.apk

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature

// DER tags.
private const val TAG_INTEGER = 0x02
private const val TAG_BIT_STRING = 0x03
private const val TAG_NULL = 0x05
private const val TAG_OID = 0x06
private const val TAG_UTF8_STRING = 0x0C
private const val TAG_UTC_TIME = 0x17
private const val TAG_GENERALIZED_TIME = 0x18
private const val TAG_SEQUENCE = 0x30
private const val TAG_SET = 0x31

/**
 * A signing identity: an RSA key pair plus the self-signed certificate that
 * vouches for it.
 */
internal class SigningIdentity(
    val keyPair: KeyPair,
    val certificate: ByteArray,
) {
    val publicKeyInfo: ByteArray get() = keyPair.public.encoded
}

/**
 * Builds a self-signed X.509 certificate by emitting DER directly.
 *
 * The JDK can generate a key pair but not a certificate to go with it, and the
 * usual answer — BouncyCastle — would add several megabytes to an APK that is
 * currently under two. The certificate a clone needs is entirely fixed apart
 * from its public key, so encoding it by hand costs a page of code and nothing
 * in APK size.
 */
internal object CertGen {

    /** 1.2.840.113549.1.1.11 sha256WithRSAEncryption */
    private val SHA256_WITH_RSA = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
        0x0D, 0x01, 0x01, 0x0B,
    )

    /** 2.5.4.3 commonName */
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    // Fixed validity window. Reading the clock would only add a way for a
    // device with a skewed date to mint a certificate that is already expired.
    private const val NOT_BEFORE = "200101000000Z"       // UTCTime, 2020-01-01
    private const val NOT_AFTER = "20740101000000Z"      // GeneralizedTime, 2074

    fun generate(commonName: String = "OpenClone"): SigningIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").run {
            initialize(2048, SecureRandom())
            generateKeyPair()
        }

        val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }
        serial[0] = (serial[0].toInt() and 0x7F).toByte() // keep it positive

        val algorithmId = der(
            TAG_SEQUENCE,
            der(TAG_OID, SHA256_WITH_RSA) + der(TAG_NULL, ByteArray(0)),
        )
        val name = der(
            TAG_SEQUENCE,
            der(
                TAG_SET,
                der(
                    TAG_SEQUENCE,
                    der(TAG_OID, COMMON_NAME) +
                        der(TAG_UTF8_STRING, commonName.toByteArray(Charsets.UTF_8)),
                ),
            ),
        )
        val validity = der(
            TAG_SEQUENCE,
            der(TAG_UTC_TIME, NOT_BEFORE.toByteArray(Charsets.US_ASCII)) +
                der(TAG_GENERALIZED_TIME, NOT_AFTER.toByteArray(Charsets.US_ASCII)),
        )

        // A v1 certificate: no extensions are required to sign an APK, and
        // omitting them keeps the structure minimal.
        val tbs = der(
            TAG_SEQUENCE,
            der(TAG_INTEGER, serial) +
                algorithmId +
                name +
                validity +
                name +
                keyPair.public.encoded, // already a DER SubjectPublicKeyInfo
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }

        val certificate = der(
            TAG_SEQUENCE,
            tbs + algorithmId + der(TAG_BIT_STRING, byteArrayOf(0) + signature),
        )
        return SigningIdentity(keyPair, certificate)
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val length = encodeLength(content.size)
        val out = ByteArray(1 + length.size + content.size)
        out[0] = tag.toByte()
        System.arraycopy(length, 0, out, 1, length.size)
        System.arraycopy(content, 0, out, 1 + length.size, content.size)
        return out
    }

    /** DER definite-length form: short below 128, else a big-endian byte count. */
    private fun encodeLength(length: Int): ByteArray {
        if (length < 0x80) return byteArrayOf(length.toByte())
        var significant = 4
        while (significant > 1 && (length ushr ((significant - 1) * 8)) == 0) significant--
        val out = ByteArray(1 + significant)
        out[0] = (0x80 or significant).toByte()
        for (i in 0 until significant) {
            out[1 + i] = (length ushr ((significant - 1 - i) * 8)).toByte()
        }
        return out
    }
}
