package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.model.SmtpUser


public abstract class SmtpUserHandler {
    public abstract fun verify(searchTerm: String): Collection<SmtpUser>
}
