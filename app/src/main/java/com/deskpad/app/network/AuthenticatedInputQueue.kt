package com.deskpad.app.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** Created per connection; nothing may be queued before auth or after disconnect. */
internal class AuthenticatedInputQueue(capacity: Int = 256) {
    enum class Offer { ACCEPTED, DISCONNECTED, OVERFLOW }
    private val queue = Channel<String>(capacity)
    val outgoing: ReceiveChannel<String> get() = queue
    private var accepting = false
    private var closed = false

    @Synchronized fun authenticate() { if (!closed) accepting = true }
    @Synchronized fun offer(line: String): Offer {
        if (!accepting || closed) return Offer.DISCONNECTED
        SecureFrames.encode(line)
        if (queue.trySend(line).isSuccess) return Offer.ACCEPTED
        close()
        return Offer.OVERFLOW
    }
    @Synchronized fun close() {
        closed = true
        accepting = false
        queue.cancel()
    }
}
