package io.github.kotlinsmtp.spi.pipeline

/**
 * SMTP command stages where interceptor decisions can be applied.
 */
public enum class SmtpCommandStage {
    MAIL_FROM,
    RCPT_TO,
    DATA_PRE,
}

/**
 * Interceptor decision applied to command processing flow.
 */
public sealed interface SmtpCommandInterceptorAction {
    /** Continue to next interceptor or core command processor. */
    public data object Proceed : SmtpCommandInterceptorAction

    /**
     * Reject command with explicit SMTP response.
     *
     * @property statusCode SMTP status code.
     * @property message SMTP response message.
     */
    public data class Deny(
        public val statusCode: Int,
        public val message: String,
    ) : SmtpCommandInterceptorAction

    /**
     * Drop connection immediately, optionally with response first.
     *
     * @property responseCode Optional response code sent before close.
     * @property responseMessage Optional response message sent before close.
     */
    public data class Drop(
        public val responseCode: Int? = null,
        public val responseMessage: String? = null,
    ) : SmtpCommandInterceptorAction
}

/**
 * Context shared across interceptors for a command evaluation.
 *
 * @property sessionId Session identifier.
 * @property peerAddress Effective peer address.
 * @property serverHostname Server host name.
 * @property helo Current HELO/EHLO value.
 * @property greeted Whether HELO/EHLO has been completed.
 * @property tlsActive Whether TLS is active.
 * @property authenticated Whether session is authenticated.
 * @property requireAuthForMail Whether MAIL requires authenticated TLS session.
 * @property mailFrom Current envelope sender.
 * @property recipientCount Current envelope recipient count.
 * @property commandName Command name (uppercase).
 * @property rawCommand Raw command line.
 * @property rawWithoutCommand Command arguments part.
 * @property attributes Session-scoped read-only attributes view.
 */
public data class SmtpCommandInterceptorContext(
    public val sessionId: String,
    public val peerAddress: String?,
    public val serverHostname: String?,
    public val helo: String?,
    public val greeted: Boolean,
    public val tlsActive: Boolean,
    public val authenticated: Boolean,
    public val requireAuthForMail: Boolean,
    public val mailFrom: String?,
    public val recipientCount: Int,
    public val commandName: String,
    public val rawCommand: String,
    public val rawWithoutCommand: String,
    public val attributes: Map<String, Any?>,
)

/**
 * Command interceptor SPI for policy/extension chaining.
 */
public interface SmtpCommandInterceptor {
    /**
     * Interceptor order. Lower value runs first.
     */
    public val order: Int
        get() = 0

    /**
     * Intercept command stage and return control decision.
     *
     * @param stage Stage being intercepted.
     * @param context Session and command context.
     * @return Interceptor action.
     */
    public suspend fun intercept(
        stage: SmtpCommandStage,
        context: SmtpCommandInterceptorContext,
    ): SmtpCommandInterceptorAction = SmtpCommandInterceptorAction.Proceed
}
