package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.model.SessionData
import java.io.InputStream

/**
 * Handler that provides hooks for SMTP transaction processing.
 *
 * - Register via [io.github.kotlinsmtp.server.SmtpServerBuilder.useProtocolHandlerFactory].
 * - [sessionData] is initialized by engine and must not be accessed in constructor/init block.
 */
public abstract class SmtpProtocolHandler {
    public lateinit var sessionData: SessionData
        internal set

    internal fun init(sessionData: SessionData) {
        this.sessionData = sessionData
    }

    /**
     * Called when MAIL FROM is received.
     *
     * @param sender Sender address (normalized/validated value)
     */
    public open suspend fun from(sender: String): Unit {}

    /**
     * Called when RCPT TO is received.
     *
     * @param recipient Recipient address (normalized/validated value)
     */
    public open suspend fun to(recipient: String): Unit {}

    /**
     * Called when DATA/BDAT body is received.
     *
     * @param inputStream Raw message stream (consumption is implementation responsibility)
     * @param size Message size (bytes)
     */
    public open suspend fun data(inputStream: InputStream, size: Long): Unit {}

    /**
     * Called when one transaction is completed.
     *
     * - May include normal completion (e.g., receiving ".") or termination by RSET, etc.
     */
    public open suspend fun done(): Unit {}
}
