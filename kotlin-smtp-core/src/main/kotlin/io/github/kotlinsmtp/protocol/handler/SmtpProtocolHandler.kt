package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.model.SessionData
import java.io.InputStream

public abstract class SmtpProtocolHandler {
    public lateinit var sessionData: SessionData
        internal set

    internal fun init(sessionData: SessionData) {
        this.sessionData = sessionData
    }

    public open suspend fun from(sender: String): Unit {}

    public open suspend fun to(recipient: String): Unit {}

    public open suspend fun data(inputStream: InputStream, size: Long): Unit {}

    public open suspend fun done(): Unit {}
}
