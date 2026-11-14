package com.deskpad.app.network

import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One ephemeral SRP exchange per explicit user action. Never registers a global crypto provider. */
class SrpPinClient(
    pin: CharArray,
    peerHash: ByteArray,
    val id: String,
    salt: ByteArray,
    random: SecureRandom = SecureRandom(),
    private val srp: SRP6Client = SRP6Client()
) : AutoCloseable {
    private val peerHash = peerHash.copyOf()
    private var step = 0
    private var key: ByteArray? = null
    private val publicA: ByteArray
    init {
        require(pin.size == 6 && pin.all { it in '0'..'9' })
        require(peerHash.size == 32 && salt.size == 16)
        PinPairingProtocol.decode(id, 32)
        val password = ByteArray(pin.size) { pin[it].code.toByte() }
        try {
            srp.init(SRP6StandardGroups.rfc5054_3072, SHA256Digest(), random)
            val identity = identity(peerHash, id).toByteArray(Charsets.US_ASCII)
            publicA = fixed(srp.generateClientCredentials(salt, identity, password), 384)
        } finally {
            password.fill(0)
            pin.fill('\u0000')
        }
    }
    fun publicValue(): ByteArray = publicA.copyOf()
    fun answer(serverB: ByteArray): ByteArray {
        check(step == 0)
        step = 1
        require(serverB.size == 384)
        val b = BigInteger(1, serverB)
        require(b > BigInteger.ZERO && b < SRP6StandardGroups.rfc5054_3072.n)
        srp.calculateSecret(b)
        return fixed(srp.calculateClientEvidenceMessage(), 32)
    }
    fun verifyAndConfirm(serverM2: ByteArray): ByteArray {
        check(step == 1)
        step = 2
        require(serverM2.size == 32 && srp.verifyServerEvidenceMessage(BigInteger(1, serverM2)))
        val sessionKey = fixed(srp.calculateSessionKey(), 32)
        key = sessionKey
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        return mac.doFinal(confirmContext(peerHash, id).toByteArray(Charsets.US_ASCII))
    }
    override fun close() { key?.fill(0); key = null; step = 3 }
    override fun toString(): String = "SrpPinClient(private pairing attempt)"
    companion object {
        fun identity(hash: ByteArray, id: String): String = "DeskPad-PIN-v1|${hex(hash)}|$id"
        fun confirmContext(hash: ByteArray, id: String): String = "DeskPad-PIN-v1-confirm|$id|${hex(hash)}"
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun fixed(value: BigInteger, size: Int) = BigIntegers.asUnsignedByteArray(size, value)
    }
}

/** The enrollment wire is deliberately smaller and stricter than general JSON. */
object PinPairingProtocol {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String, size: Int): ByteArray {
        require(text.length == (size * 8 + 5) / 6 && text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
        val bytes = Base64.getUrlDecoder().decode(text)
        require(bytes.size == size && encode(bytes) == text)
        return bytes
    }
    fun read(line: String, type: String, fields: Set<String>): Map<String, String> {
        require(line.length in 1..4096 && line.all { it.code in 32..126 })
        var at = 0
        fun spaces() { while (at < line.length && line[at] == ' ') at++ }
        fun expect(c: Char) { spaces(); require(at < line.length && line[at++] == c) }
        fun string(): String {
            expect('"')
            val start = at
            while (at < line.length && line[at] != '"') { require(line[at] != '\\'); at++ }
            require(at < line.length)
            return line.substring(start, at++)
        }
        val values = linkedMapOf<String, String>()
        expect('{')
        while (true) {
            val key = string()
            require(!values.containsKey(key))
            expect(':')
            val value = if (key == "v") { spaces(); require(at < line.length && line[at++] == '2'); "2" } else string()
            values[key] = value
            spaces(); require(at < line.length)
            if (line[at] == '}') { at++; break }
            expect(',')
        }
        spaces(); require(at == line.length)
        if (values.keys == setOf("t", "v") && values["t"] == "pin_error") throw IllegalArgumentException("Pairing failed")
        require(values.keys == fields + setOf("t", "v") && values["t"] == type && values["v"] == "2")
        return values
    }
    fun challenge(line: String): Map<String, String> = read(line, "pin_challenge", setOf("id", "salt", "b")).also {
        decode(it.getValue("id"), 32); decode(it.getValue("salt"), 16)
        val b = BigInteger(1, decode(it.getValue("b"), 384))
        require(b > BigInteger.ZERO && b < SRP6StandardGroups.rfc5054_3072.n)
    }
    fun ready(line: String, id: String): ByteArray = read(line, "pin_ready", setOf("id", "m2")).let {
        require(it["id"] == id); decode(it.getValue("m2"), 32)
    }
    fun paired(line: String, id: String, observedHash: ByteArray): PairingCode = read(line, "pin_paired", setOf("id", "code")).let {
        require(it["id"] == id)
        val raw = it.getValue("code")
        val pairing = PairingCode.parse(raw)
        require(pairing.encoded() == raw && MessageDigest.isEqual(pairing.pinBytes(), observedHash))
        pairing
    }
}
