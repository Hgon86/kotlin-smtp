package io.github.kotlinsmtp.relay.jakarta

import io.github.kotlinsmtp.relay.api.RelayException
import io.github.kotlinsmtp.relay.api.RelayPermanentException
import io.github.kotlinsmtp.relay.api.RelayTransientException
import jakarta.mail.AuthenticationFailedException
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException
import org.eclipse.angus.mail.util.MailConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Classifies relay exceptions into permanent/transient categories.
 */
internal object RelayFailureClassifier {
    private val enhancedStatusRegex = Regex("\\b([245]\\.\\d{1,3}\\.\\d{1,3})\\b")

    /**
     * Maps transport exceptions to relay exceptions with retry semantics.
     *
     * @param error Original exception from SMTP transport
     * @param fallbackMessage Fallback message when no explicit reply exists
     * @return Classified relay exception with optional enhanced status metadata
     */
    fun classify(error: Exception, fallbackMessage: String): RelayException {
        if (error is RelayException) return error

        val returnCode = smtpReturnCode(error)
        val enhancedStatus = extractEnhancedStatus(error.message)
        val remoteReply = error.message?.takeIf { it.isNotBlank() }
        val message = remoteReply ?: fallbackMessage

        if (error is AuthenticationFailedException) {
            return RelayPermanentException(message, error, enhancedStatus ?: "5.7.8", remoteReply)
        }

        if (error is UnknownHostException || error is SocketTimeoutException || error is MailConnectException) {
            return RelayTransientException(message, error, enhancedStatus ?: "4.4.1", remoteReply)
        }

        if (returnCode != null) {
            return if (returnCode in 500..599) {
                RelayPermanentException(message, error, enhancedStatus ?: "5.0.0", remoteReply)
            } else {
                RelayTransientException(message, error, enhancedStatus ?: "4.0.0", remoteReply)
            }
        }

        if (enhancedStatus != null) {
            return if (enhancedStatus.startsWith("5.")) {
                RelayPermanentException(message, error, enhancedStatus, remoteReply)
            } else {
                RelayTransientException(message, error, enhancedStatus, remoteReply)
            }
        }

        return RelayTransientException(message, error, "4.4.1", remoteReply)
    }

    private fun smtpReturnCode(error: Throwable): Int? {
        val current = generateSequence(error) { it.cause }
        current.forEach { cause ->
            when (cause) {
                is SMTPAddressFailedException -> return cause.returnCode
                is SMTPSendFailedException -> return cause.returnCode
                is SMTPSenderFailedException -> return cause.returnCode
            }
        }
        return null
    }

    private fun extractEnhancedStatus(message: String?): String? {
        if (message.isNullOrBlank()) return null
        return enhancedStatusRegex.find(message)?.groupValues?.getOrNull(1)
    }
}
