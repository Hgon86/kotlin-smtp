package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptor
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorAction
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorContext
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandStage

/**
 * Runs command interceptors in deterministic order and short-circuits on non-proceed actions.
 *
 * @property interceptors Registered command interceptors.
 */
internal class SmtpCommandInterceptorChainRunner(
    interceptors: List<SmtpCommandInterceptor>,
) {
    private val orderedInterceptors: List<SmtpCommandInterceptor> = interceptors
        .withIndex()
        .sortedWith(compareBy({ it.value.order }, { it.index }))
        .map { it.value }

    /**
     * Runs interceptors for a specific stage.
     *
     * @param stage SMTP command stage.
     * @param context Interceptor context.
     * @return First non-proceed action, or proceed when all pass.
     */
    suspend fun run(
        stage: SmtpCommandStage,
        context: SmtpCommandInterceptorContext,
    ): SmtpCommandInterceptorAction {
        for (interceptor in orderedInterceptors) {
            val action = interceptor.intercept(stage, context)
            if (action !is SmtpCommandInterceptorAction.Proceed) {
                return action
            }
        }
        return SmtpCommandInterceptorAction.Proceed
    }
}
