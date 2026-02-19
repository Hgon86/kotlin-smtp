package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.model.RcptDsn
import io.github.kotlinsmtp.relay.api.RelayException
import java.net.UnknownHostException

/**
 * Encapsulates spool-delivery failure classification and DSN sending policy.
 */
internal class SpoolFailurePolicy {
    /**
     * Compute whether FAILURE DSN should be sent based on NOTIFY parameter.
     *
     * @param notify Raw RCPT-level NOTIFY parameter
     * @return true when FAILURE DSN should be sent
     */
    fun shouldSendFailureDsn(notify: String?): Boolean {
        val tokens = parseNotifyTokens(notify) ?: return true
        if ("NEVER" in tokens) return false
        return "FAILURE" in tokens || tokens.isEmpty()
    }

    /**
     * Filter DSN send targets from failed recipients.
     *
     * @param failures Per-recipient failure reasons
     * @param rcptDsn Per-recipient DSN options
     * @return DSN-target map
     */
    fun selectFailureDsnTargets(
        failures: Map<String, String>,
        rcptDsn: Map<String, RcptDsn>,
    ): Map<String, String> = failures.filterKeys { shouldSendFailureDsn(rcptDsn[it]?.notify) }

    /**
     * Classify whether exception is permanent failure (no retry needed).
     *
     * @param throwable Cause of delivery failure
     * @return true for permanent failure, otherwise false
     */
    fun isPermanentFailure(throwable: Throwable): Boolean {
        when (throwable) {
            is io.github.kotlinsmtp.exception.SmtpSendResponse -> return throwable.statusCode in 500..599
            is RelayException -> return !throwable.isTransient
            is IllegalStateException -> {
                val message = throwable.message.orEmpty()
                return message.contains("No MX records", ignoreCase = true) ||
                    message.contains("No valid MX", ignoreCase = true)
            }
            is UnknownHostException -> return false
        }

        val returnCode = smtpReturnCodeOrNull(throwable)
        if (returnCode != null) return returnCode in 500..599

        val enhancedCode = enhancedCodeOrNull(throwable.message)
        if (enhancedCode != null) return enhancedCode.first() == '5'

        return false
    }

    /**
     * Parse NOTIFY parameter into token set.
     *
     * @param notify Raw RCPT-level NOTIFY parameter
     * @return Parsed token set, or null when input is empty
     */
    private fun parseNotifyTokens(notify: String?): Set<String>? {
        val raw = notify?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return raw
            .split(',')
            .asSequence()
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun smtpReturnCodeOrNull(throwable: Throwable): Int? = runCatching {
        val method = throwable.javaClass.methods.firstOrNull {
            it.name == "getReturnCode" && it.parameterCount == 0
        } ?: return null
        method.invoke(throwable) as? Int
    }.getOrNull()

    private fun enhancedCodeOrNull(message: String?): String? {
        if (message.isNullOrBlank()) return null
        val match = ENHANCED_CODE_REGEX.find(message) ?: return null
        return match.groupValues.getOrNull(1)
    }
}

private val ENHANCED_CODE_REGEX = Regex("\\b(\\d\\.\\d\\.\\d)\\b")
