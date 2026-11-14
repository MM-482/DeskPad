package com.deskpad.app.network

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

internal interface QrConnectionAttempt {
    val authentication: Deferred<Boolean>
    fun close()
}

/** Shared retry logic is independent of Android and never treats a TCP connection as authentication. */
internal object QrHostConnector {
    suspend fun connect(
        hosts: List<String>,
        isCurrent: () -> Boolean,
        start: (String) -> QrConnectionAttempt?,
        overallTimeoutMs: Long = 10000L,
        maxHostTimeoutMs: Long = 3000L
    ): Boolean {
        var pending: QrConnectionAttempt? = null
        val deadline = System.nanoTime() + overallTimeoutMs * 1_000_000L
        return try {
            withTimeoutOrNull(overallTimeoutMs) overall@{
                for ((index, host) in hosts.withIndex()) {
                    if (!isCurrent()) return@overall false
                    val remaining = (deadline - System.nanoTime()) / 1_000_000L
                    if (remaining <= 0) return@overall false
                    val attempt = start(host) ?: return@overall false
                    pending = attempt
                    val budget = (remaining / (hosts.size - index)).coerceAtLeast(1L).coerceAtMost(maxHostTimeoutMs)
                    val authenticated = withTimeoutOrNull(budget) { attempt.authentication.await() } == true
                    if (authenticated && isCurrent()) {
                        pending = null // The authenticated session now owns its socket and input loop.
                        return@overall true
                    }
                    attempt.close()
                    pending = null
                    if (!isCurrent()) return@overall false
                }
                false
            } == true
        } finally {
            // Includes external cancellation, overall timeout and stale results.
            pending?.close()
        }
    }
}
