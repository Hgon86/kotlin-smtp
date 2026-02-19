package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.model.SessionData
import io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler

/**
 * Manages transaction handler lifecycle for a session.
 *
 * @property creator Handler factory
 */
internal class SmtpProtocolHandlerHolder(
    private val creator: (() -> SmtpProtocolHandler)?,
) {
    @Volatile
    private var current: SmtpProtocolHandler? = null

    /**
     * Create and return handler when needed.
     *
     * @param sessionData Session data used for handler initialization
     * @return Current handler or newly created handler
     */
    fun getOrCreate(sessionData: SessionData): SmtpProtocolHandler? {
        val existing = current
        if (existing != null) return existing

        val factory = creator ?: return null
        return synchronized(this) {
            current
                ?: factory.invoke().also {
                    it.init(sessionData)
                    current = it
                }
        }
    }

    /**
     * Finalize handler and clear reference.
     */
    suspend fun doneAndClear() {
        val handler = synchronized(this) {
            val value = current
            current = null
            value
        }
        handler?.done()
    }
}
