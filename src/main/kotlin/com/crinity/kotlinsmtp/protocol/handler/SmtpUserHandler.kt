package com.crinity.kotlinsmtp.protocol.handler

import com.crinity.kotlinsmtp.model.SmtpUser


abstract class SmtpUserHandler {
    abstract fun verify(searchTerm: String): Collection<SmtpUser>
}
