package com.deskpad.app

import com.deskpad.app.network.QrConnectionAttempt
import com.deskpad.app.network.QrHostConnector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class QrHostConnectorTest {
    private class Attempt(result: Boolean? = null) : QrConnectionAttempt {
        override val authentication = CompletableDeferred<Boolean>().apply { result?.let { complete(it) } }
        var closed = false
        override fun close() { closed = true; authentication.complete(false) }
    }

    @Test fun verifiedAuth_retriesSequentiallyAndKeepsOnlySuccessfulAttempt() = runBlocking {
        val visited = mutableListOf<String>()
        val first = Attempt(false)
        val second = Attempt(true)
        assertTrue(QrHostConnector.connect(listOf("one", "two"), { true }, {
            visited.add(it); if (it == "one") first else second
        }))
        assertEquals(listOf("one", "two"), visited)
        assertTrue(first.closed)
        assertFalse(second.closed)
    }

    @Test fun deadFirstHost_doesNotMonopolizeOverallBudget() = runBlocking {
        withTimeout(2000) {
            val first = Attempt()
            val second = Attempt(true)
            val visited = mutableListOf<String>()
            assertTrue(QrHostConnector.connect(listOf("dead", "live"), { true }, {
                visited.add(it); if (it == "dead") first else second
            }, overallTimeoutMs = 1000, maxHostTimeoutMs = 15))
            assertEquals(listOf("dead", "live"), visited)
            assertTrue(first.closed)
            assertFalse(second.closed)
        }
    }

    @Test fun staleGeneration_evenRacingWithAuthSuccessClosesCandidate() = runBlocking {
        var current = true
        val stale = Attempt(true)
        var starts = 0
        assertFalse(QrHostConnector.connect(listOf("old", "never"), { current }, {
            starts++; current = false; stale
        }))
        assertEquals(1, starts)
        assertTrue(stale.closed)
    }

    @Test fun explicitCancellation_closesPendingAttemptAndNeverTriesAnother() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val attempt = Attempt()
        var starts = 0
        val task = launch {
            QrHostConnector.connect(listOf("one", "two"), { true }, {
                starts++; started.complete(Unit); attempt
            })
        }
        started.await()
        task.cancelAndJoin()
        assertEquals(1, starts)
        assertTrue(attempt.closed)
    }

    @Test fun overallTimeout_closesPendingAttemptAndReturnsFailure() = runBlocking {
        withTimeout(2000) {
            val attempt = Attempt()
            assertFalse(QrHostConnector.connect(listOf("unresponsive"), { true }, { attempt },
                overallTimeoutMs = 20, maxHostTimeoutMs = 1000))
            assertTrue(attempt.closed)
        }
    }
}
