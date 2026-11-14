package com.deskpad.app.network

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

/** The pin and secret arrive out of band from the PC, never from network discovery. */
class PairingCode private constructor(
    private val certificatePin: ByteArray,
    private val secret: ByteArray
) {
    val token: String get() = encode(secret)
    val pinId: String get() = certificatePin.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun pinBytes(): ByteArray = certificatePin.copyOf()
    fun encoded(): String = PREFIX + encode(certificatePin + secret)

    companion object {
        private const val PREFIX = "DP2:"
        private val alphabet = Regex("[A-Za-z0-9_-]{86}")

        fun parse(value: String): PairingCode {
            val text = value.trim()
            require(text.startsWith(PREFIX)) { "Paste the DP2 pairing code shown on your PC" }
            val payload = text.removePrefix(PREFIX)
            require(alphabet.matches(payload)) { "The pairing code is incomplete or invalid" }
            val bytes = try {
                Base64.getUrlDecoder().decode(payload)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("The pairing code is invalid")
            }
            require(bytes.size == 64 && encode(bytes) == payload) { "The pairing code is invalid" }
            return PairingCode(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
        }

        private fun encode(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

object CertificatePinning {
    fun matches(expected: ByteArray, certificateDer: ByteArray): Boolean =
        expected.size == 32 && MessageDigest.isEqual(
            expected,
            MessageDigest.getInstance("SHA-256").digest(certificateDer)
        )
}

/** This accepts one exact, pre-imported certificate; there is no trust-all or CA fallback. */
internal class PinnedCertificateTrustManager(expectedPin: ByteArray) : X509TrustManager {
    private val pin = expectedPin.copyOf().also { require(it.size == 32) }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("PC certificate missing")
        if (!CertificatePinning.matches(pin, leaf.encoded)) {
            throw CertificateException("PC identity changed. Import its pairing code again to continue.")
        }
        leaf.checkValidity()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted here")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
