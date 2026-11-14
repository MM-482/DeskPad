package com.deskpad.app

import com.deskpad.app.model.ModifierState
import com.deskpad.app.model.MouseButton
import com.deskpad.app.model.PointerMotionAccumulator
import com.deskpad.app.model.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolAndAccelerationTest {

    @Test
    fun parseDiscoveryOffer_validPayload_returnsHost() {
        val host = ProtocolCodec.parseDiscoveryOffer(
            "DESKPAD_OFFER_V2|Mateo-PC|Windows|48912",
            "192.168.1.42"
        )
        assertNotNull(host)
        assertEquals("Mateo-PC", host!!.name)
        assertEquals("Windows", host.os)
        assertEquals("192.168.1.42", host.ip)
        assertEquals(48912, host.tcpPort)
    }

    @Test
    fun parseDiscoveryOffer_invalidHeader_returnsNull() {
        assertNull(ProtocolCodec.parseDiscoveryOffer("OTHER|pc|win|1|2", "10.0.0.2"))
    }

    @Test
    fun tcpKeyPacket_escapesQuotesAndBackslashes() {
        val pkt = ProtocolCodec.tcpKeyPacket(
            "\\",
            ModifierState(ctrl = true, shift = true)
        )
        assertTrue(pkt.contains(""""k":"\\""""))
        assertTrue(pkt.contains(""""m":["CTRL","SHIFT"]"""))
    }

    @Test
    fun tcpClickPacket_formatsCorrectly() {
        assertEquals(
            """{"t":"click","b":"R","c":1}""",
            ProtocolCodec.tcpClickPacket(MouseButton.RIGHT, 1)
        )
    }

    @Test
    fun pointerAccumulator_preservesSubPixelRemainders() {
        val acc = PointerMotionAccumulator()
        val (dx1, dy1) = acc.accumulate(0.3f, 0.3f, 1.0f)
        val (dx2, dy2) = acc.accumulate(0.3f, 0.3f, 1.0f)
        assertEquals(0, dx1)
        assertEquals(0, dy1)
        assertEquals(1, dx2)
        assertEquals(1, dy2)
    }

    @Test
    fun clampInt8_boundsValues() {
        assertEquals(127.toByte(), ProtocolCodec.clampInt8(400))
        assertEquals((-127).toByte(), ProtocolCodec.clampInt8(-300))
        assertEquals(42.toByte(), ProtocolCodec.clampInt8(42))
    }
}
