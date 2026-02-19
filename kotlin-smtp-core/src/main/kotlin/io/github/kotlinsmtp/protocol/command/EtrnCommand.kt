package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SpoolTriggerResult
import io.github.kotlinsmtp.server.SmtpDomainSpooler
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.AddressUtils
import io.github.kotlinsmtp.utils.SmtpStatusCode

/**
 * ETRN (RFC 1985)
 *
 * Not a common command in production, but useful as a "queue (spool) immediate processing" trigger in operations/admin scenarios.
 *
 * - Default is disabled for internet exposure (enableEtrn=false)
 * - Even when enabled, allow only authenticated sessions to prevent abuse.
 */
internal class EtrnCommand : SmtpCommand(
    "ETRN",
    "Requests that the server attempt to process the queue for the given domain (admin/management).",
    "<domain>"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (!session.server.enableEtrn) {
            throw SmtpSendResponse(SmtpStatusCode.COMMAND_NOT_IMPLEMENTED.code, "ETRN not supported")
        }
        if (!session.sessionData.isAuthenticated) {
            throw SmtpSendResponse(530, "5.7.0 Authentication required")
        }

        val queueDomain = normalizeQueueDomain(command.rawWithoutCommand)
            ?: respondSyntax("Invalid ETRN domain argument")

        val spooler = session.server.spooler
            ?: throw SmtpSendResponse(SmtpStatusCode.SERVICE_NOT_AVAILABLE.code, "Queue service not available")

        val (result, responseMessage) = runCatching {
            if (spooler is SmtpDomainSpooler) {
                val result = spooler.tryTriggerOnce(queueDomain)
                result to "Queue run triggered for $queueDomain"
            } else {
                // For spoolers without domain support, fall back to existing behavior (trigger whole queue).
                val result = spooler.tryTriggerOnce()
                result to "Queue run triggered (domain filter not supported)"
            }
        }.getOrElse { t ->
            throw SmtpSendResponse(451, "4.3.0 Queue trigger failed: ${t.message ?: "unknown error"}")
        }

        when (result) {
            SpoolTriggerResult.ACCEPTED -> Unit
            SpoolTriggerResult.INVALID_ARGUMENT -> respondSyntax("Invalid ETRN domain argument")
            SpoolTriggerResult.UNAVAILABLE -> {
                throw SmtpSendResponse(
                    SmtpStatusCode.SERVICE_NOT_AVAILABLE.code,
                    "Queue service not available",
                )
            }
        }

        session.sendResponse(SmtpStatusCode.OKAY.code, responseMessage)
    }

    /**
     * Normalize ETRN argument into domain form.
     *
     * @param rawArg Argument string excluding command token from the raw command line
     * @return Normalized ASCII domain, or null if invalid
     */
    private fun normalizeQueueDomain(rawArg: String): String? {
        val term = rawArg.trim()
        if (term.isEmpty()) return null
        val withoutPrefix = term.removePrefix("@").trim()
        if (withoutPrefix.isEmpty()) return null
        return AddressUtils.normalizeValidDomain(withoutPrefix)
    }
}
