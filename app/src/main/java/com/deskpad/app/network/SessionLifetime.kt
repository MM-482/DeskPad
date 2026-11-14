package com.deskpad.app.network

/** Close and authentication publication share the manager lock, so an abandoned attempt cannot commit. */
internal class SessionLifetime(private val ownerLock: Any) {
    @Volatile var isClosed = false
        private set

    fun whileOpen(action: () -> Unit): Boolean = synchronized(ownerLock) {
        if (isClosed) false else { action(); true }
    }

    fun close(cleanup: () -> Unit) = synchronized(ownerLock) {
        if (!isClosed) {
            isClosed = true
            cleanup()
        }
    }
}
