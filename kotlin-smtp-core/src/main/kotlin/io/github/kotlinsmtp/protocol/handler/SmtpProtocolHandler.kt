package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.model.SessionData
import java.io.InputStream

abstract class SmtpProtocolHandler {
    lateinit var sessionData: SessionData

    internal fun init(sessionData: SessionData) {
        this.sessionData = sessionData
    }

    open suspend fun from(sender: String) {}

    open suspend fun to(recipient: String) {}

    open suspend fun data(inputStream: InputStream, size: Long) {}

    open suspend fun done() {}
}
