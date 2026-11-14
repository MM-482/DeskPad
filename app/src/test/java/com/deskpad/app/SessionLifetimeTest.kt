package com.deskpad.app

import com.deskpad.app.network.SessionLifetime
import org.junit.Assert.*
import org.junit.Test

class SessionLifetimeTest {
    @Test fun lateAuthenticationAfterTimeoutClose_cannotPersistOrPublish() {
        val lifetime = SessionLifetime(Any())
        var saved = false
        var connected = false
        lifetime.close { }
        assertFalse(lifetime.whileOpen { saved = true; connected = true })
        assertFalse(saved)
        assertFalse(connected)
    }

    @Test fun closedAttempt_cannotAttachAReplacementSocket() {
        val lifetime = SessionLifetime(Any())
        lifetime.close { }
        var attached = false
        assertFalse(lifetime.whileOpen { attached = true })
        assertFalse(attached)
        assertTrue(lifetime.isClosed)
    }

    @Test fun cleanupIsIdempotentAndNewSessionRemainsIndependent() {
        val owner = Any()
        val old = SessionLifetime(owner)
        val current = SessionLifetime(owner)
        var cleanups = 0
        old.close { cleanups++ }
        old.close { cleanups++ }
        assertEquals(1, cleanups)
        assertTrue(current.whileOpen { })
        assertFalse(current.isClosed)
    }
}
