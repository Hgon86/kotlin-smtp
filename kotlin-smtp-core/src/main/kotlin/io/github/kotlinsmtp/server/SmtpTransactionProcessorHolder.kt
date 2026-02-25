package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.model.SessionData
import io.github.kotlinsmtp.protocol.handler.SmtpTransactionProcessor

/**
 * Manages transaction processor lifecycle for a session.
 *
 * @property creator Processor factory
 */
internal class SmtpTransactionProcessorHolder(
    private val creator: (() -> SmtpTransactionProcessor)?,
) {
    @Volatile
    private var current: SmtpTransactionProcessor? = null

    /**
     * Create and return processor when needed.
     *
     * @param sessionData Session data used for processor initialization
     * @return Current processor or newly created processor
     */
    fun getOrCreate(sessionData: SessionData): SmtpTransactionProcessor? {
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
     * Finalize processor and clear reference.
     */
    suspend fun doneAndClear() {
        val processor = synchronized(this) {
            val value = current
            current = null
            value
        }
        processor?.done()
    }
}
