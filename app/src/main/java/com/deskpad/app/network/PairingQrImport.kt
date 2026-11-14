package com.deskpad.app.network

/** Scanner results remain in memory and reuse the same canonical DP2 validation as manual entry. */
object PairingQrImport {
    fun read(contents: String?): PairingQrResult {
        if (contents == null) return PairingQrResult.Cancelled
        if (contents.length > 200) return PairingQrResult.Invalid
        return try {
            PairingQrResult.Ready(PairingCode.parse(contents))
        } catch (_: IllegalArgumentException) {
            PairingQrResult.Invalid
        }
    }
}

sealed interface PairingQrResult {
    data object Cancelled : PairingQrResult
    data object Invalid : PairingQrResult
    class Ready internal constructor(private val value: PairingCode) : PairingQrResult {
        fun pairingCode(): String = value.encoded()
        override fun toString(): String = "Ready(private pairing QR)"
    }
}
