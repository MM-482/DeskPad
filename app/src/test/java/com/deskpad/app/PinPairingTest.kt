package com.deskpad.app

import com.deskpad.app.network.PinPairingProtocol as Wire
import com.deskpad.app.network.SrpPinClient
import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class PinPairingTest {
    private val vector = Properties().apply { PinPairingTest::class.java.getResourceAsStream("/pin-final-profile.properties")!!.use { load(it) } }
    private fun bytes(key: String) = vector.getProperty(key).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val id get() = vector.getProperty("id")
    private fun fixedClient() = object : SRP6Client() {
        override fun selectPrivateValue(): BigInteger = BigInteger("60975527035CF2AD1989806F0407210BC81EDC04E2762A56AFD529DDDA2D4393", 16)
    }
    private fun client(pin: String = "042731", hash: ByteArray = bytes("cert_hash_hex")) =
        SrpPinClient(pin.toCharArray(), hash, id, bytes("salt_hex"), srp = fixedClient())
    private fun challenge(b: ByteArray = bytes("B_hex")) = """{"t":"pin_challenge","v":2,"id":"$id","salt":"${Wire.encode(bytes("salt_hex"))}","b":"${Wire.encode(b)}"}"""

    @Test fun finalProfile_productionHelperMatchesIndependentFixedVector() {
        val pin = "042731".toCharArray()
        SrpPinClient(pin, bytes("cert_hash_hex"), id, bytes("salt_hex"), srp = fixedClient()).use { client ->
            assertTrue(pin.all { it == '\u0000' })
            assertArrayEquals(bytes("A_hex"), client.publicValue())
            assertArrayEquals(bytes("M1_hex"), client.answer(bytes("B_hex")))
            assertArrayEquals(bytes("confirm_hex"), client.verifyAndConfirm(bytes("M2_hex")))
        }
    }
    @Test fun mutualProofMustPrecedeConfirmation_andExchangeCannotRepeat() {
        client().use {
            assertThrows(IllegalStateException::class.java) { it.verifyAndConfirm(bytes("M2_hex")) }
            it.answer(bytes("B_hex"))
            assertThrows(IllegalStateException::class.java) { it.answer(bytes("B_hex")) }
            it.verifyAndConfirm(bytes("M2_hex"))
            assertThrows(IllegalStateException::class.java) { it.verifyAndConfirm(bytes("M2_hex")) }
        }
    }
    @Test fun wrongPinOrObservedTlsLeafCannotAcceptServerProof() {
        val wrongHash = bytes("cert_hash_hex").also { it[0] = (it[0].toInt() xor 1).toByte() }
        listOf(client("142731"), client(hash = wrongHash)).forEach { c -> c.use {
            assertFalse(bytes("M1_hex").contentEquals(it.answer(bytes("B_hex"))))
            assertThrows(IllegalArgumentException::class.java) { it.verifyAndConfirm(bytes("M2_hex")) }
        } }
    }
    @Test fun tamperedM2AndOutOfRangePublicValuesFailClosed() {
        client().use { c ->
            c.answer(bytes("B_hex"))
            val m2 = bytes("M2_hex").also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertThrows(IllegalArgumentException::class.java) { c.verifyAndConfirm(m2) }
        }
        listOf(ByteArray(384), BigIntegers.asUnsignedByteArray(384, SRP6StandardGroups.rfc5054_3072.n), ByteArray(383)).forEach { b ->
            client().use { c -> assertThrows(IllegalArgumentException::class.java) { c.answer(b) } }
            assertThrows(IllegalArgumentException::class.java) { Wire.challenge(challenge(b)) }
        }
    }
    @Test fun pinRequiresSixAsciiDigits_andPreservesLeadingZero() {
        listOf("12345", "1234567", "１２３４５６", "12a456", "123 56").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { client(bad) }
        }
        assertArrayEquals(bytes("M1_hex"), client().use { it.answer(bytes("B_hex")) })
    }
    @Test fun wireRejectsDuplicateUnknownEscapedAndCoercedKeys() {
        val valid = challenge()
        assertEquals(id, Wire.challenge(valid)["id"])
        listOf(valid.replace("\"v\":2", "\"v\":\"2\""), valid.replace("\"v\":2", "\"v\":2.0"),
            valid.replace("\"v\":2", "\"v\":2,\"v\":2"), valid.replace("\"salt\"", "\"other\""),
            valid.replace("pin_challenge", "pin_\\u0063hallenge"), valid + "x", valid.replace("{", "{\n"),
            valid.replace("\"b\":\"", "\"b\":null,\"extra\":\"")).forEach { bad ->
            assertThrows(bad.take(80), IllegalArgumentException::class.java) { Wire.challenge(bad) }
        }
    }
    @Test fun canonicalBase64FixedWidthsAndSessionIdAreRequired() {
        listOf(id + "=", id.dropLast(1), id.dropLast(1) + "9", id.replace('A','+')).forEach {
            assertThrows(IllegalArgumentException::class.java) { Wire.decode(it, 32) }
        }
        assertThrows(IllegalArgumentException::class.java) { Wire.ready("""{"t":"pin_ready","v":2,"id":"other","m2":"${Wire.encode(bytes("M2_hex"))}"}""", id) }
        assertThrows(IllegalArgumentException::class.java) { Wire.ready("""{"t":"pin_error","v":2}""", id) }
    }
    @Test fun issuedPairingMustBeCanonicalAndPinObservedCertificate() {
        val hash = bytes("cert_hash_hex")
        val code = "DP2:" + Wire.encode(hash + ByteArray(32) { (it + 70).toByte() })
        fun frame(value: String) = """{"t":"pin_paired","v":2,"id":"$id","code":"$value"}"""
        assertEquals(code, Wire.paired(frame(code), id, hash).encoded())
        assertThrows(IllegalArgumentException::class.java) { Wire.paired(frame(code), id, ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { Wire.paired(frame(" $code"), id, hash) }
    }
}
