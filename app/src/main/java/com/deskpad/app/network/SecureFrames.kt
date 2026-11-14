package com.deskpad.app.network

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A frame has at most MAX_BYTES UTF-8 payload bytes followed by a single LF. */
object SecureFrames {
    const val MAX_BYTES = 16384

    fun write(output: OutputStream, payload: String) {
        val bytes = encode(payload)
        output.write(bytes)
        output.write(10)
        output.flush()
    }

    fun encode(payload: String): ByteArray {
        require(!payload.contains('\n') && !payload.contains('\r')) { "Frame contains a line break" }
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Frame exceeds the protocol limit" }
        return bytes
    }

    /** The caller owns buffering, deadlines, cancellation and the stream. */
    fun read(input: InputStream, beforeRead: () -> Unit = {}): String? {
        val frame = ByteArrayOutputStream()
        while (true) {
            beforeRead()
            val next = input.read()
            if (next == -1) {
                if (frame.size() == 0) return null
                throw EOFException("PC closed an incomplete frame")
            }
            if (next == 13) throw IOException("Unexpected CR in protocol frame")
            if (next == 10) {
                if (frame.size() == 0) throw IOException("Empty protocol frame")
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(frame.toByteArray())).toString()
            }
            if (frame.size() >= MAX_BYTES) throw IOException("PC frame exceeds the protocol limit")
            frame.write(next)
        }
    }
}
