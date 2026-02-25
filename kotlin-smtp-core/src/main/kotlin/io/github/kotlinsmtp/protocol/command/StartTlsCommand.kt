package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.COMMAND_NOT_IMPLEMENTED
import io.github.kotlinsmtp.utils.SmtpStatusCode.SERVICE_READY

internal class StartTlsCommand : SmtpCommand(
    "STARTTLS",
    "Upgrade connection to TLS",
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (command.parts.size != 1) {
            session.sendResponse(501, "5.5.1 STARTTLS takes no parameters")
            return
        }

        if (session.isTls) {
            // Return error if already in TLS connection state
            session.sendResponse(503, "5.5.1 TLS already active")
            return
        }

        if (!session.server.enableStartTls) {
            session.sendResponse(COMMAND_NOT_IMPLEMENTED.code, "5.5.1 STARTTLS not supported on this service")
            return
        }

        // TLS cert/key may not be configured even when STARTTLS is advertised; reject gracefully.
        if (session.server.sslContext == null) {
            session.sendResponse(454, "4.7.0 TLS not available")
            return
        }

        // STARTTLS cannot be pipelined.
        // - Reject if there is already queued input (next command/data), since it can break upgrade transition.
        val upgradeOk = session.beginStartTlsUpgrade()
        if (!upgradeOk) {
            session.sendResponseAwait(501, "5.5.1 STARTTLS cannot be pipelined")
            session.shouldQuit = true
            session.close()
            return
        }

        // RFC 3207 convention: 220 Ready to start TLS
        // Important: this line must be flushed in plaintext before inserting SslHandler into pipeline.
        session.sendResponseAwait(SERVICE_READY.code, "Ready to start TLS")

        // Start TLS handshake and update pipeline
        session.finishStartTlsUpgrade()
    }
}
