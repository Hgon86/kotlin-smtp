package io.github.kotlinsmtp.exception

/**
 * Exception for control flow to immediately return a specific SMTP response.
 *
 * When this exception is thrown during command processing, the upper layer responds with the status code/message.
 *
 * @property statusCode SMTP status code (e.g., 250, 550)
 * @property message Response message (text after status code)
 */
public class SmtpSendResponse(
    public val statusCode: Int,
    override val message: String,
) : Exception(message)
