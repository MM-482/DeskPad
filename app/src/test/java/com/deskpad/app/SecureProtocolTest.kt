package com.deskpad.app

import com.deskpad.app.model.ProtocolCodec
import com.deskpad.app.network.AuthenticatedInputQueue
import com.deskpad.app.network.CertificatePinning
import com.deskpad.app.network.PairingCode
import com.deskpad.app.network.SecureFrames
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SecureProtocolTest {
    private fun pairing(bytes: ByteArray = ByteArray(64) { it.toByte() }): String =
        "DP2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    @Test fun pairingCode_separatesExactPinAndToken_withoutPadding() {
        val bytes = ByteArray(64) { it.toByte() }
        val value = PairingCode.parse(pairing(bytes))
        assertArrayEquals(bytes.copyOfRange(0, 32), value.pinBytes())
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.copyOfRange(32, 64)), value.token)
        assertEquals(43, value.token.length)
        assertEquals(pairing(bytes), value.encoded())
        assertEquals(value.encoded(), PairingCode.parse("  ${value.encoded()}\n").encoded())
    }

    @Test fun pairingCode_rejectsLegacyMalformedPaddedAndNonCanonicalInput() {
        val valid = pairing()
        val badPaddingBits = valid.dropLast(1) + "x" // Same decoded payload as final 'w', but noncanonical bits.
        listOf("", "123456", valid.replace("DP2:", "DP1:"), valid + "=", valid.dropLast(1),
            pairing(ByteArray(63)), pairing(ByteArray(65)), valid.replaceRange(8, 9, "/"), badPaddingBits).forEach {
            assertThrows("must reject malformed pairing code", IllegalArgumentException::class.java) { PairingCode.parse(it) }
        }
    }

    @Test fun pairingPin_isDefensivelyCopied() {
        val parsed = PairingCode.parse(pairing())
        val first = parsed.pinBytes()
        first[0] = 99
        assertEquals(0.toByte(), parsed.pinBytes()[0])
    }

    @Test fun certificatePin_requiresExactSha256OfEntireDer() {
        val der = byteArrayOf(1, 3, 5, 7, 9)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(der)
        assertTrue(CertificatePinning.matches(fingerprint, der))
        assertFalse(CertificatePinning.matches(fingerprint, der + 0.toByte()))
        assertFalse(CertificatePinning.matches(ByteArray(31), der))
        fingerprint[0] = (fingerprint[0].toInt() xor 1).toByte()
        assertFalse(CertificatePinning.matches(fingerprint, der))
    }

    @Test fun discovery_rejectsLegacyAndMalformedOffers() {
        listOf(
            "DESKPAD_OFFER_V1|PC|Windows|48912|48911",
            "DESKPAD_OFFER_V2|PC|Windows|48912|48911",
            "DESKPAD_OFFER_V2|PC|Windows", "DESKPAD_OFFER_V2||Windows|48912",
            "DESKPAD_OFFER_V2|PC|Linux|48912", "DESKPAD_OFFER_V2|PC|Windows|noport",
            "DESKPAD_OFFER_V2|PC|Windows|0", "DESKPAD_OFFER_V2|PC|Windows|65536",
            "DESKPAD_OFFER_V2|PC\nforged|Windows|48912",
            "DESKPAD_OFFER_V2|${"a".repeat(129)}|Windows|48912"
        ).forEach { assertNull(it, ProtocolCodec.parseDiscoveryOffer(it, "192.168.1.2")) }
        assertEquals("DESKPAD_DISCOVER_V2", ProtocolCodec.DISCOVER_QUERY)
    }

    @Test fun allMotionUsesJsonFramesInsteadOfUdpPayloads() {
        assertEquals("""{"t":"move","dx":3,"dy":-4}""", ProtocolCodec.tcpMovePacket(3, -4))
        assertEquals("""{"t":"scroll","sx":1,"sy":-2}""", ProtocolCodec.tcpScrollPacket(1, -2))
        assertEquals("""{"t":"zoom","delta":5}""", ProtocolCodec.tcpZoomPacket(5))
    }

    @Test fun text_isBoundedInUtf16UnitsAndEscapesAllJsonControls() {
        assertTrue(ProtocolCodec.tcpTextPacket("a".repeat(2048)).contains("a".repeat(2048)))
        assertThrows(IllegalArgumentException::class.java) { ProtocolCodec.tcpTextPacket("a".repeat(2049)) }
        val emoji = "\uD83D\uDE03"
        ProtocolCodec.tcpTextPacket(emoji.repeat(1024))
        assertThrows(IllegalArgumentException::class.java) { ProtocolCodec.tcpTextPacket(emoji.repeat(1025)) }
        val packet = ProtocolCodec.tcpTextPacket("\u0000\u0001\b\n\r\t\"\\")
        assertFalse(packet.any { it.code < 32 })
        assertTrue(packet.contains("\\u0000"))
        assertTrue(packet.contains("\\u0008"))
        assertTrue(packet.contains("\\n\\r\\t"))
    }

    @Test fun frames_enforceUtf8ByteLimitAndSingleLfDelimiter() {
        val payload = "é".repeat(8192)
        assertEquals(16384, SecureFrames.encode(payload).size)
        assertThrows(IllegalArgumentException::class.java) { SecureFrames.encode(payload + "é") }
        assertThrows(IllegalArgumentException::class.java) { SecureFrames.encode("a\nb") }
        assertThrows(IllegalArgumentException::class.java) { SecureFrames.encode("a\rb") }
        val output = ByteArrayOutputStream()
        SecureFrames.write(output, payload)
        assertEquals(payload, SecureFrames.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test fun frames_rejectOversizeTruncatedCrEmptyAndInvalidUtf8() {
        assertThrows(IOException::class.java) { SecureFrames.read(ByteArrayInputStream(("x".repeat(16385) + "\n").toByteArray())) }
        assertThrows(EOFException::class.java) { SecureFrames.read(ByteArrayInputStream("{}".toByteArray())) }
        assertThrows(IOException::class.java) { SecureFrames.read(ByteArrayInputStream("{}\r\n".toByteArray())) }
        assertThrows(IOException::class.java) { SecureFrames.read(ByteArrayInputStream(byteArrayOf(10))) }
        assertThrows(IOException::class.java) { SecureFrames.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28, 10))) }
        assertNull(SecureFrames.read(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun frames_doNotConsumeNextMessage_andHonorDeadlineHook() {
        val input = ByteArrayInputStream("one\ntwo\n".toByteArray())
        assertEquals("one", SecureFrames.read(input))
        assertEquals("two", SecureFrames.read(input))
        assertThrows(IOException::class.java) {
            SecureFrames.read(ByteArrayInputStream("x\n".toByteArray())) { throw IOException("deadline") }
        }
    }

    @Test fun sessionQueue_dropsBeforeAuthAndAfterCloseWithoutReplay() {
        val old = AuthenticatedInputQueue(2)
        assertEquals(AuthenticatedInputQueue.Offer.DISCONNECTED, old.offer("before"))
        old.authenticate()
        assertEquals(AuthenticatedInputQueue.Offer.ACCEPTED, old.offer("old-click"))
        old.close()
        old.authenticate()
        assertEquals(AuthenticatedInputQueue.Offer.DISCONNECTED, old.offer("after"))
        assertTrue(old.outgoing.tryReceive().isFailure)
        val next = AuthenticatedInputQueue(2)
        next.authenticate()
        assertTrue(next.outgoing.tryReceive().isFailure)
    }

    @Test fun sessionQueue_overflowClosesQueueInsteadOfDroppingReleaseAndContinuing() {
        val queue = AuthenticatedInputQueue(1)
        queue.authenticate()
        assertEquals(AuthenticatedInputQueue.Offer.ACCEPTED, queue.offer("button-down"))
        assertEquals(AuthenticatedInputQueue.Offer.OVERFLOW, queue.offer("button-up"))
        assertEquals(AuthenticatedInputQueue.Offer.DISCONNECTED, queue.offer("later-click"))
        assertTrue(queue.outgoing.tryReceive().isFailure)
    }
}
