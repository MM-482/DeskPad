package com.deskpad.app.network

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** A foreground PC QR carries its local addresses and the unchanged, private DP2 credential. */
class PairingEnvelope private constructor(
    val name: String,
    val hosts: List<String>,
    val port: Int,
    private val code: String
) {
    fun pairingCode(): String = code
    override fun toString(): String = "PairingEnvelope(private PC pairing)"

    companion object {
        fun parse(payload: String): PairingEnvelope {
            require(payload.length <= 2048) { "Invalid DeskPad pairing QR" }
            val utf8 = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(payload))
            require(utf8.remaining() <= 2048) { "Invalid DeskPad pairing QR" }
            val reader = EnvelopeReader(payload)
            val seen = mutableSetOf<String>()
            var name: String? = null
            var hosts: List<String>? = null
            var code: String? = null
            reader.expect('{')
            do {
                val key = reader.string()
                require(seen.add(key)) { "Invalid DeskPad pairing QR" }
                reader.expect(':')
                when (key) {
                    "t" -> require(reader.string() == "deskpad_pair")
                    "v" -> require(reader.integer() == 2)
                    "port" -> require(reader.integer() == 48912)
                    "name" -> name = reader.string().also {
                        require(it.length in 1..64 && it.isNotBlank() && it.none { ch -> Character.isISOControl(ch) })
                    }
                    "hosts" -> hosts = reader.hosts()
                    "code" -> {
                        val supplied = reader.string()
                        require(PairingCode.parse(supplied).encoded() == supplied)
                        code = supplied
                    }
                    else -> throw IllegalArgumentException("Invalid DeskPad pairing QR")
                }
            } while (reader.take(','))
            reader.expect('}')
            reader.requireEnd()
            require(seen == setOf("t", "v", "name", "hosts", "port", "code"))
            return PairingEnvelope(requireNotNull(name), requireNotNull(hosts), 48912, requireNotNull(code))
        }

        fun isAllowedHost(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            val octets = parts.map {
                if (it.isEmpty() || it.length > 3 || it.any { ch -> ch !in '0'..'9' } ||
                    (it.length > 1 && it[0] == '0')) return false
                it.toIntOrNull()?.takeIf { number -> number in 0..255 } ?: return false
            }
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 100 && octets[1] in 64..127)
        }
    }
}

/** Strict, bounded JSON for this six-field schema: rejects duplicate/unknown keys and coercions. */
private class EnvelopeReader(private val input: String) {
    private var position = 0
    private fun whitespace() { while (position < input.length && input[position] in " \t\r\n") position++ }
    fun take(ch: Char): Boolean {
        whitespace()
        if (position >= input.length || input[position] != ch) return false
        position++
        return true
    }
    fun expect(ch: Char) { require(take(ch)) { "Invalid DeskPad pairing QR" } }
    fun requireEnd() { whitespace(); require(position == input.length) }
    fun integer(): Int {
        whitespace()
        val start = position
        while (position < input.length && input[position] in '0'..'9') position++
        val text = input.substring(start, position)
        require(text.isNotEmpty() && (text.length == 1 || text[0] != '0'))
        return text.toIntOrNull() ?: throw IllegalArgumentException("Invalid DeskPad pairing QR")
    }
    fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (position < input.length) {
            val ch = input[position++]
            when {
                ch == '"' -> {
                    val value = result.toString()
                    var i = 0
                    while (i < value.length) {
                        val character = value[i]
                        if (Character.isHighSurrogate(character)) {
                            require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1]))
                            i += 2
                        } else {
                            require(!Character.isLowSurrogate(character))
                            i++
                        }
                    }
                    return value
                }
                ch.code < 32 -> throw IllegalArgumentException("Invalid DeskPad pairing QR")
                ch == '\\' -> {
                    require(position < input.length)
                    when (val escaped = input[position++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(position + 4 <= input.length)
                            val hex = input.substring(position, position + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                            result.append(hex.toInt(16).toChar())
                            position += 4
                        }
                        else -> throw IllegalArgumentException("Invalid DeskPad pairing QR")
                    }
                }
                else -> result.append(ch)
            }
        }
        throw IllegalArgumentException("Invalid DeskPad pairing QR")
    }
    fun hosts(): List<String> {
        expect('[')
        val result = mutableListOf<String>()
        do {
            val host = string()
            require(result.size < 8 && PairingEnvelope.isAllowedHost(host) && host !in result)
            result.add(host)
        } while (take(','))
        expect(']')
        return result.toList()
    }
}

object PairingEnvelopeImport {
    fun read(contents: String?): PairingEnvelopeResult {
        if (contents == null) return PairingEnvelopeResult.Cancelled
        return try {
            PairingEnvelopeResult.Ready(PairingEnvelope.parse(contents))
        } catch (_: Exception) {
            PairingEnvelopeResult.Invalid
        }
    }
}

sealed interface PairingEnvelopeResult {
    data object Cancelled : PairingEnvelopeResult
    data object Invalid : PairingEnvelopeResult
    class Ready(val envelope: PairingEnvelope) : PairingEnvelopeResult {
        override fun toString(): String = "Ready(private PC pairing QR)"
    }
}
