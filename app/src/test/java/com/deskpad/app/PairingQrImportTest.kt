package com.deskpad.app

import com.deskpad.app.network.PairingQrImport
import com.deskpad.app.network.PairingQrResult
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PairingQrImportTest {
    // Deterministic dummy bytes only, never a live PC credential.
    private val code = "DP2:" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(64) { it.toByte() })

    @Test fun validQr_reusesCanonicalDp2BundleWithoutChangingPinOrToken() {
        val scanned = PairingQrImport.read(code) as PairingQrResult.Ready
        assertEquals(code, scanned.pairingCode())
    }

    @Test fun surroundingWhitespace_isCanonicalized() {
        val scanned = PairingQrImport.read("  $code\n") as PairingQrResult.Ready
        assertEquals(code, scanned.pairingCode())
    }

    @Test fun cancelledScan_hasNoCredential() {
        assertSame(PairingQrResult.Cancelled, PairingQrImport.read(null))
    }

    @Test fun wrongQrOrMalformedBundle_cannotReachConnectionCallbackAsReady() {
        listOf("", "123456", "https://example.com/", code + "=", code.dropLast(1),
            code.replace("DP2:", "DP1:"), code.dropLast(1) + "x", "x".repeat(201)).forEach {
            assertSame(PairingQrResult.Invalid, PairingQrImport.read(it))
        }
    }

    @Test fun resultDescription_doesNotExposeCredential() {
        val result = PairingQrImport.read(code)
        assertFalse(result.toString().contains(code))
        assertFalse(result.toString().contains("DP2:"))
    }
}
