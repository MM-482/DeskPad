package com.deskpad.app

import com.deskpad.app.network.PairingEnvelope
import com.deskpad.app.network.PairingEnvelopeImport
import com.deskpad.app.network.PairingEnvelopeResult
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PairingEnvelopeTest {
    private val code = "DP2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64) { it.toByte() })
    private fun envelope(hosts: String = "\"192.168.56.10\",\"100.64.0.10\"", name: String = "DeskPad Test PC") =
        """{"t":"deskpad_pair","v":2,"name":"$name","hosts":[$hosts],"port":48912,"code":"$code"}"""
    private fun rejects(text: String) = assertSame(PairingEnvelopeResult.Invalid, PairingEnvelopeImport.read(text))

    @Test fun directEnvelope_containsEverythingNeededWithoutHostSelection() {
        val value = (PairingEnvelopeImport.read(envelope()) as PairingEnvelopeResult.Ready).envelope
        assertEquals("DeskPad Test PC", value.name)
        assertEquals(listOf("192.168.56.10", "100.64.0.10"), value.hosts)
        assertEquals(48912, value.port)
        assertEquals(code, value.pairingCode())
    }

    @Test fun fieldOrderWhitespaceAndJsonEscapes_areAccepted() {
        val value = PairingEnvelope.parse(""" { "code":"$code", "port":48912, "hosts":["10.1.2.3"], "name":"DeskPad \u0054est PC", "v":2, "t":"deskpad_pair" } """)
        assertEquals("DeskPad Test PC", value.name)
        assertEquals(listOf("10.1.2.3"), value.hosts)
    }

    @Test fun exactSchema_rejectsMissingExtraDuplicateOrCoercedFields() {
        val valid = envelope()
        listOf(
            valid.replace("\"v\":2", "\"v\":\"2\""), valid.replace("\"v\":2", "\"v\":2.0"),
            valid.replace("\"port\":48912", "\"port\":48911"), valid.replace("\"port\":48912", "\"port\":048912"),
            valid.replace("\"t\":\"deskpad_pair\"", "\"t\":\"other\""),
            valid.replace("\"name\":\"DeskPad Test PC\",", ""), valid.dropLast(1) + ",\"extra\":1}",
            "{\"v\":2," + valid.removePrefix("{"), "{\"\\u0076\":2," + valid.removePrefix("{"),
            valid + "trailing", valid.replace("\"v\":2", "v:2"), valid.replace("\"v\":2", "\"v\":true")
        ).forEach(::rejects)
    }

    @Test fun hosts_acceptOnlyCanonicalRfc1918AndCgnat() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1", "100.64.0.1", "100.127.255.254").forEach {
            assertTrue(it, PairingEnvelope.isAllowedHost(it))
            PairingEnvelope.parse(envelope("\"$it\""))
        }
    }

    @Test fun hosts_rejectDnsUrlsPublicLoopbackLinkLocalIpv6AndNonCanonicalForms() {
        listOf("localhost", "example.com", "http://192.168.1.1", "8.8.8.8", "127.0.0.1", "169.254.1.2",
            "172.15.1.1", "172.32.1.1", "100.63.1.1", "100.128.1.1", "0.0.0.0", "224.0.0.1", "::1",
            "192.168.001.1", "192.168.1", "192.168.1.256", "+10.1.2.3", " 10.1.2.3").forEach {
            assertFalse(it, PairingEnvelope.isAllowedHost(it))
            rejects(envelope("\"$it\""))
        }
    }

    @Test fun hosts_areNonEmptyUniqueAndBoundedToEight() {
        rejects(envelope(""))
        rejects(envelope("\"10.1.2.3\",\"10.1.2.3\""))
        rejects(envelope((1..9).joinToString(",") { "\"10.1.2.$it\"" }))
        assertEquals(8, PairingEnvelope.parse(envelope((1..8).joinToString(",") { "\"10.1.2.$it\"" })).hosts.size)
    }

    @Test fun names_areBoundedAndCannotContainControlsOrUnpairedSurrogates() {
        listOf("", " ", "a".repeat(65), "a\\nPC", "a\\u007fPC", "a\\u0085PC", "\\ud800").forEach {
            rejects(envelope(name = it))
        }
        PairingEnvelope.parse(envelope(name = "a".repeat(64)))
    }

    @Test fun canonicalCredential_isRequiredAndBareDp2NeverAutoConnects() {
        rejects(code)
        rejects(envelope().replace(code, "$code="))
        rejects(envelope().replace(code, " $code"))
        rejects(envelope().replace(code, code.dropLast(1) + "x"))
        rejects(envelope().replace("DP2:", "DP1:"))
    }

    @Test fun envelope_isBoundedByUtf8BytesAndCharacterCount() {
        rejects(" ".repeat(2049))
        val base = envelope()
        rejects(base + " ".repeat(2049 - base.length))
        val multibyte = envelope(name = "界".repeat(64))
        val byteOversize = multibyte + " ".repeat(2048 - multibyte.length)
        assertEquals(2048, byteOversize.length)
        assertTrue(byteOversize.toByteArray(Charsets.UTF_8).size > 2048)
        rejects(byteOversize)
        // Escaped names are individually bounded even when the whole envelope fits the transport bound.
        rejects(envelope(name = "\\u0061".repeat(65)))
    }

    @Test fun cancellationAndDescriptions_doNotExposeTheCredential() {
        assertSame(PairingEnvelopeResult.Cancelled, PairingEnvelopeImport.read(null))
        val parsed = PairingEnvelopeImport.read(envelope()) as PairingEnvelopeResult.Ready
        assertFalse(parsed.toString().contains(code))
        assertFalse(parsed.envelope.toString().contains(code))
    }
}
