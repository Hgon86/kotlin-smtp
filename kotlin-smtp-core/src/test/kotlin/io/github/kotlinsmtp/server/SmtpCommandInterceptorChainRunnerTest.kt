package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptor
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorAction
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorContext
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandStage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmtpCommandInterceptorChainRunnerTest {

    @Test
    fun run_executesInterceptorsInOrder() = runBlocking {
        val trace = mutableListOf<String>()
        val runner = SmtpCommandInterceptorChainRunner(
            listOf(
                namedInterceptor("third", order = 30, trace = trace),
                namedInterceptor("first", order = 10, trace = trace),
                namedInterceptor("second", order = 20, trace = trace),
            ),
        )

        val action = runner.run(
            stage = SmtpCommandStage.MAIL_FROM,
            context = sampleContext(),
        )

        assertTrue(action is SmtpCommandInterceptorAction.Proceed)
        assertEquals(listOf("first", "second", "third"), trace)
    }

    @Test
    fun run_shortCircuitsOnDeny() = runBlocking {
        val trace = mutableListOf<String>()
        val runner = SmtpCommandInterceptorChainRunner(
            listOf(
                namedInterceptor("first", order = 10, trace = trace),
                object : SmtpCommandInterceptor {
                    override val order: Int = 20

                    override suspend fun intercept(
                        stage: SmtpCommandStage,
                        context: SmtpCommandInterceptorContext,
                    ): SmtpCommandInterceptorAction {
                        trace += "deny"
                        return SmtpCommandInterceptorAction.Deny(550, "Denied by test")
                    }
                },
                namedInterceptor("should-not-run", order = 30, trace = trace),
            ),
        )

        val action = runner.run(
            stage = SmtpCommandStage.RCPT_TO,
            context = sampleContext(),
        )

        assertEquals(listOf("first", "deny"), trace)
        assertEquals(SmtpCommandInterceptorAction.Deny(550, "Denied by test"), action)
    }

    private fun namedInterceptor(
        name: String,
        order: Int,
        trace: MutableList<String>,
    ): SmtpCommandInterceptor = object : SmtpCommandInterceptor {
        override val order: Int = order

        override suspend fun intercept(
            stage: SmtpCommandStage,
            context: SmtpCommandInterceptorContext,
        ): SmtpCommandInterceptorAction {
            trace += name
            return SmtpCommandInterceptorAction.Proceed
        }
    }

    private fun sampleContext(): SmtpCommandInterceptorContext =
        SmtpCommandInterceptorContext(
            sessionId = "session-1",
            peerAddress = "127.0.0.1:2525",
            serverHostname = "test.local",
            helo = "test.client.local",
            greeted = true,
            tlsActive = false,
            authenticated = false,
            requireAuthForMail = false,
            mailFrom = null,
            recipientCount = 0,
            commandName = "MAIL",
            rawCommand = "MAIL FROM:<user@test.local>",
            rawWithoutCommand = "FROM:<user@test.local>",
            attributes = mutableMapOf(),
        )
}
