package com.deskpad.app

import com.deskpad.app.network.PairingQrImport
import com.deskpad.app.network.PairingQrResult
import com.deskpad.app.network.PairingEnvelopeImport
import com.deskpad.app.network.PairingEnvelopeResult
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import java.io.DataInputStream
import java.io.InputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingQrRoundTripTest {
    @Test fun windowsProductionRenderer_isDecodedByAndroidZxingAndCanonicalImporter() {
        val decoded = decode("/windows-pairing-dummy.pgm")
        val expected = "DP2:" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(64) { it.toByte() })
        assertEquals(BarcodeFormat.QR_CODE, decoded.barcodeFormat)
        assertEquals(expected, decoded.text)
        val imported = PairingQrImport.read(decoded.text) as PairingQrResult.Ready
        assertEquals(expected, imported.pairingCode())
    }

    @Test fun windowsProductionEnvelopeQr_importsAddressAndCredentialWithoutSelection() {
        val decoded = decode("/windows-pairing-envelope-dummy.pgm")
        val imported = (PairingEnvelopeImport.read(decoded.text) as PairingEnvelopeResult.Ready).envelope
        val expected = "DP2:" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(64) { it.toByte() })
        assertEquals(BarcodeFormat.QR_CODE, decoded.barcodeFormat)
        assertEquals("DeskPad Test PC", imported.name)
        assertEquals(listOf("192.168.56.10", "100.64.0.10"), imported.hosts)
        assertEquals(48912, imported.port)
        assertEquals(expected, imported.pairingCode())
    }

    private fun decode(resource: String): com.google.zxing.Result {
        // Lossless luminance raster from Windows' production PNG, using dummy bytes 0..63 only.
        // Android's unit-test boot classpath excludes java.desktop/ImageIO, so use plain PGM bytes.
        val stream = requireNotNull(javaClass.getResourceAsStream(resource))
        val source = stream.use {
            assertEquals("P5", token(it))
            val width = token(it).toInt()
            val height = token(it).toInt()
            assertEquals("255", token(it))
            require(width in 1..2048 && height in 1..2048)
            val pixels = ByteArray(width * height)
            DataInputStream(it).readFully(pixels)
            RasterLuminanceSource(width, height, pixels)
        }
        return MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        )
    }

    private fun token(input: InputStream): String {
        val result = StringBuilder()
        var next = input.read()
        while (next in 0..32) next = input.read()
        while (next > 32) {
            require(result.length < 32)
            result.append(next.toChar())
            next = input.read()
        }
        require(result.isNotEmpty())
        return result.toString()
    }

    private class RasterLuminanceSource(width: Int, height: Int, private val pixels: ByteArray) : LuminanceSource(width, height) {
        override fun getMatrix(): ByteArray = pixels.copyOf()
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            require(y in 0 until height)
            val output = row?.takeIf { it.size >= width } ?: ByteArray(width)
            pixels.copyInto(output, 0, y * width, (y + 1) * width)
            return output
        }
    }
}
