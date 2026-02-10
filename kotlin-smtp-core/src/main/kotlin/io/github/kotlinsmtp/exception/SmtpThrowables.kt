package io.github.kotlinsmtp.exception

/**
 * 특정 SMTP 응답을 즉시 반환하기 위한 제어 흐름용 예외입니다.
 *
 * 커맨드 처리 중 이 예외를 던지면, 상위 레이어가 상태 코드/메시지로 응답합니다.
 *
 * @property statusCode SMTP 상태 코드(예: 250, 550)
 * @property message 응답 메시지(상태 코드 뒤 텍스트)
 */
public class SmtpSendResponse(
    public val statusCode: Int,
    override val message: String,
) : Exception(message)
