package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.model.SmtpUser


abstract class SmtpUserHandler {
    abstract fun verify(searchTerm: String): Collection<SmtpUser>
}
