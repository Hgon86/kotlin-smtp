package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptor
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorAction
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorContext
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandStage

/**
 * Default command policy guard that preserves core SMTP sequencing and submission security.
 */
internal class SmtpDefaultCommandPolicyInterceptor : SmtpCommandInterceptor {
    override val order: Int = -1000

    override suspend fun intercept(
        stage: SmtpCommandStage,
        context: SmtpCommandInterceptorContext,
    ): SmtpCommandInterceptorAction {
        return when (stage) {
            SmtpCommandStage.MAIL_FROM -> enforceMailFromPrerequisites(context)
            SmtpCommandStage.RCPT_TO -> enforceRcptPrerequisites(context)
            SmtpCommandStage.DATA_PRE -> enforceDataPrePrerequisites(context)
        }
    }

    private fun enforceMailFromPrerequisites(
        context: SmtpCommandInterceptorContext,
    ): SmtpCommandInterceptorAction {
        if (!context.greeted) {
            return SmtpCommandInterceptorAction.Deny(503, "Send HELO/EHLO first")
        }

        if (!context.requireAuthForMail) {
            return SmtpCommandInterceptorAction.Proceed
        }

        if (!context.tlsActive) {
            return SmtpCommandInterceptorAction.Deny(530, "5.7.0 Must issue STARTTLS first")
        }

        if (!context.authenticated) {
            return SmtpCommandInterceptorAction.Deny(530, "5.7.0 Authentication required")
        }

        return SmtpCommandInterceptorAction.Proceed
    }

    private fun enforceRcptPrerequisites(
        context: SmtpCommandInterceptorContext,
    ): SmtpCommandInterceptorAction {
        if (context.mailFrom == null) {
            return SmtpCommandInterceptorAction.Deny(503, "Send MAIL FROM first")
        }

        return SmtpCommandInterceptorAction.Proceed
    }

    private fun enforceDataPrePrerequisites(
        context: SmtpCommandInterceptorContext,
    ): SmtpCommandInterceptorAction {
        // BDAT must be validated inside BdatCommand after draining declared bytes
        // to preserve stream synchronization.
        if (context.commandName != "DATA") {
            return SmtpCommandInterceptorAction.Proceed
        }

        if (context.mailFrom == null || context.recipientCount <= 0) {
            return SmtpCommandInterceptorAction.Deny(503, "Send MAIL FROM and RCPT TO first")
        }

        return SmtpCommandInterceptorAction.Proceed
    }
}
