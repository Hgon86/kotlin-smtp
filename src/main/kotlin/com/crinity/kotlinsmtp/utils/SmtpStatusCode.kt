package com.crinity.kotlinsmtp.utils

enum class SmtpStatusCode(
    val code: Int,
    val enhancedCode: String,
    val defaultDescription: String
) {
    // 2xx - Positive Completion Reply
    SYSTEM_INFO(211, "2.0.0", "System status, or system help reply"),
    HELP_MESSAGE(214, "2.0.0", "Help message"),
    SERVICE_READY(220, "2.0.0", "Service ready"),
    SERVICE_CLOSING_CHANNEL(221, "2.0.0", "Service closing transmission channel"),
    OKAY(250, "2.0.0", "Requested mail action okay, completed"),
    USER_NOT_LOCAL_WILL_FORWARD(251, "2.1.5", "User not local; will forward"),
    CANNOT_VERIFY_USER(252, "2.1.5", "Cannot verify user, but will accept message and attempt delivery"),

    // 3xx - Positive Intermediate Reply
    START_MAIL_INPUT(354, "2.0.0", "Start mail input; end with <CRLF>.<CRLF>"),

    // 4xx - Transient Negative Completion Reply
    SERVICE_NOT_AVAILABLE(421, "4.3.0", "Service not available, closing transmission channel"),
    MAILBOX_TEMPORARILY_UNAVAILABLE(450, "4.2.0", "Requested mail action not taken: mailbox unavailable"),
    ERROR_IN_PROCESSING(451, "4.3.0", "Requested action aborted: local error in processing"),
    INSUFFICIENT_STORAGE(452, "4.3.1", "Requested action not taken: insufficient system storage"),
    CANNOT_ACCOMMODATE_PARAMETERS(455, "4.5.0", "Server unable to accommodate parameters"),

    // 5xx - Permanent Negative Completion Reply
    COMMAND_REJECTED(500, "5.5.2", "Syntax error, command unrecognized"),
    COMMAND_SYNTAX_ERROR(501, "5.5.2", "Syntax error in parameters or arguments"),
    COMMAND_NOT_IMPLEMENTED(502, "5.5.1", "Command not implemented"),
    BAD_COMMAND_SEQUENCE(503, "5.5.1", "Bad sequence of commands"),
    COMMAND_PARAMETER_NOT_IMPLEMENTED(504, "5.5.4", "Command parameter not implemented"),
    MAILBOX_UNAVAILABLE(550, "5.1.1", "Requested action not taken: mailbox unavailable"),
    USER_NOT_LOCAL_TRY_OTHER(551, "5.1.6", "User not local; please try other address"),
    EXCEEDED_STORAGE_ALLOCATION(552, "5.3.4", "Requested mail action aborted: exceeded storage allocation"),
    INVALID_MAILBOX(553, "5.1.3", "Requested action not taken: mailbox name not allowed"),
    TRANSACTION_FAILED(554, "5.0.0", "Transaction failed"),
    RECIPIENT_NOT_RECOGNIZED(555, "5.5.4", "MAIL FROM/RCPT TO parameters not recognized or not implemented");

    /**
     * 확장 상태 코드를 포함한 응답 메시지 생성
     *
     * @param customMessage 기본 설명 대신 사용할 사용자 정의 메시지 (null 이면 기본 설명 사용)
     * @return 형식화된 응답 문자열
     */
    fun formatResponse(customMessage: String? = null): String {
        val responseMessage = customMessage ?: defaultDescription
        return "$code $enhancedCode $responseMessage"
    }

    companion object {
        fun fromCode(code: Int): SmtpStatusCode? = entries.find { it.code == code }
    }
}
