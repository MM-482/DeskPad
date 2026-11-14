package com.deskpad.app.network

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Unknown certificates are usable here only for a certificate-bound PAKE, never input or token auth. */
internal class PinEnrollmentTrustManager : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing certificate")
        leaf.checkValidity()
        val key = leaf.publicKey as? RSAPublicKey ?: throw CertificateException("RSA certificate required")
        if (key.modulus.bitLength() < 2048) throw CertificateException("Certificate key too small")
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not used")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal object PinEnrollmentTransport {
    /** Exactly one connection and challenge. Caller owns cancellation and must close each attached socket. */
    suspend fun enroll(ip: String, port: Int, pin: CharArray, attach: (Socket) -> Boolean): PairingCode = coroutineScope {
        val raw = Socket()
        var tls: SSLSocket? = null
        var srp: SrpPinClient? = null
        val firstEnds = System.nanoTime() + 5_000_000_000L
        val firstDeadline = launch { delay(5000); runCatching { raw.close() } }
        var exchangeDeadline: kotlinx.coroutines.Job? = null
        try {
            check(attach(raw))
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(ip, port), 5000)
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<javax.net.ssl.TrustManager>(PinEnrollmentTrustManager()), null)
            }
            val secure = context.socketFactory.createSocket(raw, ip, port, true) as SSLSocket
            tls = secure
            check(attach(secure))
            secure.enabledProtocols = secure.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            require(secure.enabledProtocols.isNotEmpty())
            secure.soTimeout = 5000
            secure.startHandshake()
            val leaf = secure.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw IOException("Missing PC certificate")
            val peerHash = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            val input = BufferedInputStream(secure.inputStream)
            val output = secure.outputStream
            SecureFrames.write(output, """{"t":"pin_begin","v":2}""")
            val challenge = PinPairingProtocol.challenge(read(secure, input, 5000))
            if (System.nanoTime() >= firstEnds) throw SocketTimeoutException()
            firstDeadline.cancel()
            val exchangeEnds = System.nanoTime() + 15_000_000_000L
            exchangeDeadline = launch { delay(15000); runCatching { secure.close() } }
            val id = challenge.getValue("id")
            val client = SrpPinClient(pin, peerHash, id, PinPairingProtocol.decode(challenge.getValue("salt"), 16))
            srp = client
            val m1 = client.answer(PinPairingProtocol.decode(challenge.getValue("b"), 384))
            SecureFrames.write(output, """{"t":"pin_proof","v":2,"id":"$id","a":"${PinPairingProtocol.encode(client.publicValue())}","m1":"${PinPairingProtocol.encode(m1)}"}""")
            val m2 = PinPairingProtocol.ready(read(secure, input, 15000), id)
            val confirmation = client.verifyAndConfirm(m2)
            SecureFrames.write(output, """{"t":"pin_confirm","v":2,"id":"$id","proof":"${PinPairingProtocol.encode(confirmation)}"}""")
            val pairing = PinPairingProtocol.paired(read(secure, input, 15000), id, peerHash)
            if (System.nanoTime() >= exchangeEnds) throw SocketTimeoutException()
            pairing
        } finally {
            pin.fill('\u0000')
            srp?.close()
            firstDeadline.cancel()
            exchangeDeadline?.cancel()
            runCatching { tls?.close() }
            runCatching { raw.close() }
        }
    }
    private fun read(socket: SSLSocket, input: BufferedInputStream, timeoutMs: Int): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        return SecureFrames.read(input) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) throw SocketTimeoutException()
            socket.soTimeout = remaining.coerceAtMost(timeoutMs.toLong()).coerceAtLeast(1).toInt()
        } ?: throw IOException("PC closed pairing")
    }
}
