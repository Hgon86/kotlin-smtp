package io.github.kotlinsmtp.exception

public class SmtpSendResponse(
    public val statusCode: Int,
    override val message: String,
) : Exception(message)
