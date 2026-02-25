package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorAction
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorContext
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandStage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SmtpDefaultCommandPolicyInterceptorTest {

    private val interceptor = SmtpDefaultCommandPolicyInterceptor()

    @Test
    fun `PRE_COMMAND blocks non-BDAT-safe commands while chunking`() = runBlocking {
        val denied = interceptor.intercept(
            stage = SmtpCommandStage.PRE_COMMAND,
            context = baseContext(commandName = "MAIL", bdatInProgress = true),
        )
        val allowed = interceptor.intercept(
            stage = SmtpCommandStage.PRE_COMMAND,
            context = baseContext(commandName = "NOOP", bdatInProgress = true),
        )

        assertEquals(
            SmtpCommandInterceptorAction.Deny(503, "BDAT in progress; send BDAT <size> [LAST] or RSET"),
            denied,
        )
        assertEquals(SmtpCommandInterceptorAction.Proceed, allowed)
    }

    @Test
    fun `PRE_COMMAND requires EHLO after STARTTLS`() = runBlocking {
        val denied = interceptor.intercept(
            stage = SmtpCommandStage.PRE_COMMAND,
            context = baseContext(commandName = "MAIL", requireEhloAfterTls = true),
        )

        assertEquals(SmtpCommandInterceptorAction.Deny(503, "Must issue HELO/EHLO after STARTTLS"), denied)
    }

    @Test
    fun `AUTH requires greeting and tls and no prior authentication`() = runBlocking {
        val requireGreeting = interceptor.intercept(
            stage = SmtpCommandStage.AUTH,
            context = baseContext(greeted = false, commandName = "AUTH"),
        )
        val requireTls = interceptor.intercept(
            stage = SmtpCommandStage.AUTH,
            context = baseContext(greeted = true, tlsActive = false, commandName = "AUTH"),
        )
        val rejectReauth = interceptor.intercept(
            stage = SmtpCommandStage.AUTH,
            context = baseContext(greeted = true, tlsActive = true, authenticated = true, commandName = "AUTH"),
        )

        assertEquals(SmtpCommandInterceptorAction.Deny(503, "Send HELO/EHLO first"), requireGreeting)
        assertEquals(SmtpCommandInterceptorAction.Deny(503, "Must issue STARTTLS first"), requireTls)
        assertEquals(SmtpCommandInterceptorAction.Deny(503, "5.5.1 Already authenticated"), rejectReauth)
    }

    @Test
    fun `MAIL requires greeting`() = runBlocking {
        val action = interceptor.intercept(
            stage = SmtpCommandStage.MAIL_FROM,
            context = baseContext(greeted = false),
        )

        assertEquals(SmtpCommandInterceptorAction.Deny(503, "Send HELO/EHLO first"), action)
    }

    @Test
    fun `MAIL requires tls and auth when requireAuthForMail enabled`() = runBlocking {
        val needTls = interceptor.intercept(
            stage = SmtpCommandStage.MAIL_FROM,
            context = baseContext(greeted = true, requireAuthForMail = true, tlsActive = false),
        )
        val needAuth = interceptor.intercept(
            stage = SmtpCommandStage.MAIL_FROM,
            context = baseContext(greeted = true, requireAuthForMail = true, tlsActive = true, authenticated = false),
        )

        assertEquals(SmtpCommandInterceptorAction.Deny(530, "5.7.0 Must issue STARTTLS first"), needTls)
        assertEquals(SmtpCommandInterceptorAction.Deny(530, "5.7.0 Authentication required"), needAuth)
    }

    @Test
    fun `RCPT requires MAIL FROM`() = runBlocking {
        val action = interceptor.intercept(
            stage = SmtpCommandStage.RCPT_TO,
            context = baseContext(mailFrom = null),
        )

        assertEquals(SmtpCommandInterceptorAction.Deny(503, "Send MAIL FROM first"), action)
    }

    @Test
    fun `DATA requires envelope state but BDAT bypasses precheck`() = runBlocking {
        val dataAction = interceptor.intercept(
            stage = SmtpCommandStage.DATA_PRE,
            context = baseContext(commandName = "DATA", mailFrom = null, recipientCount = 0),
        )
        val bdatAction = interceptor.intercept(
            stage = SmtpCommandStage.DATA_PRE,
            context = baseContext(commandName = "BDAT", mailFrom = null, recipientCount = 0),
        )

        assertEquals(SmtpCommandInterceptorAction.Deny(503, "Send MAIL FROM and RCPT TO first"), dataAction)
        assertEquals(SmtpCommandInterceptorAction.Proceed, bdatAction)
    }

    private fun baseContext(
        greeted: Boolean = true,
        tlsActive: Boolean = false,
        authenticated: Boolean = false,
        requireAuthForMail: Boolean = false,
        requireEhloAfterTls: Boolean = false,
        bdatInProgress: Boolean = false,
        mailFrom: String? = "sender@test.local",
        recipientCount: Int = 1,
        commandName: String = "MAIL",
    ): SmtpCommandInterceptorContext =
        SmtpCommandInterceptorContext(
            sessionId = "session-1",
            peerAddress = "127.0.0.1:2525",
            serverHostname = "test.local",
            helo = "test.client.local",
            greeted = greeted,
            tlsActive = tlsActive,
            authenticated = authenticated,
            requireAuthForMail = requireAuthForMail,
            mailFrom = mailFrom,
            recipientCount = recipientCount,
            commandName = commandName,
            rawCommand = commandName,
            rawWithoutCommand = "",
            attributes = mutableMapOf(),
            requireEhloAfterTls = requireEhloAfterTls,
            bdatInProgress = bdatInProgress,
        )
}
