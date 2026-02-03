package io.github.kotlinsmtp.exception

class SmtpSendResponse(val statusCode: Int, override val message: String) : Exception(message)
