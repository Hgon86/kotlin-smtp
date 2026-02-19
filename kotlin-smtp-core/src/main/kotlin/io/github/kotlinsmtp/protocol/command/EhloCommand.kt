package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY
import io.github.kotlinsmtp.utils.Values.MAX_MESSAGE_SIZE

internal class EhloCommand : SmtpCommand(
    "EHLO",
    "Extended HELO - The client introduces itself.",
    "<domain>"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (command.parts.size < 2) {
            respondSyntax("Empty HELO/EHLO argument not allowed.")
        }

        // Reset session data and store identifier
        session.resetTransaction()
        session.sessionData.helo = command.parts[1]
        session.sessionData.greeted = true
        session.sessionData.usedEhlo = true

        // Build response list (advertise only features actually supported by server)
        val responseLines = mutableListOf(
            session.server.hostname,
            // Input is processed sequentially by implementation, and commands are handled even if client pipelines without waiting for responses
            "PIPELINING",
            // 8BITMIME: supported by preserving DATA bytes 1:1 with ISO-8859-1
            "8BITMIME",
            // SMTPUTF8: accepts UTF-8 addresses (minimal implementation)
            "SMTPUTF8",
            // CHUNKING: enables chunk-based message transfer via BDAT
            "CHUNKING",
            // BINARYMIME: allows binary body transfer with CHUNKING
            "BINARYMIME",
            // DSN: accepts RET/ENVID, NOTIFY/ORCPT parameters (minimal reflection)
            "DSN",
            "SIZE $MAX_MESSAGE_SIZE",
            "ENHANCEDSTATUSCODES",
        )

        // ETRN (admin feature): exposed only via configuration
        if (session.server.enableEtrn) {
            responseLines.add("ETRN")
        }

        // Advertise STARTTLS only when TLS is configured and not already active (linked with ssl.enabled)
        if (session.server.enableStartTls && session.server.sslContext != null && !session.isTls) {
            responseLines.add("STARTTLS")
        }

        // Advertise AUTH PLAIN when TLS is active and auth service is enabled
        if (session.server.enableAuth && session.isTls && (session.server.authService?.enabled == true)) {
            // Real-world compatibility: support PLAIN + LOGIN
            responseLines.add("AUTH PLAIN LOGIN")
        }

        // Send response without enhanced code
        val code = OKAY.code
        responseLines.forEachIndexed { index, line ->
            if (index != responseLines.lastIndex)
                session.respondLine("$code-$line")
            else
                session.respondLine("$code $line")
        }
    }
}
