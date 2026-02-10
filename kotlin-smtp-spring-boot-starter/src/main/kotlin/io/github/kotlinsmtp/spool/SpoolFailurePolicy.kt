package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.model.RcptDsn
import io.github.kotlinsmtp.relay.api.RelayException
import java.net.UnknownHostException

/**
 * 스풀 배달 실패 분류 및 DSN 발송 정책을 캡슐화합니다.
 */
internal class SpoolFailurePolicy {
    /**
     * NOTIFY 파라미터를 반영해 FAILURE DSN 발송 여부를 계산합니다.
     *
     * @param notify RCPT 단위 NOTIFY 파라미터 원문
     * @return FAILURE DSN을 발송해야 하면 true
     */
    fun shouldSendFailureDsn(notify: String?): Boolean {
        val tokens = parseNotifyTokens(notify) ?: return true
        if ("NEVER" in tokens) return false
        return "FAILURE" in tokens || tokens.isEmpty()
    }

    /**
     * 실패한 수신자 중 DSN 발송 대상을 필터링합니다.
     *
     * @param failures 수신자별 실패 사유
     * @param rcptDsn 수신자별 DSN 옵션
     * @return DSN 발송 대상 맵
     */
    fun selectFailureDsnTargets(
        failures: Map<String, String>,
        rcptDsn: Map<String, RcptDsn>,
    ): Map<String, String> = failures.filterKeys { shouldSendFailureDsn(rcptDsn[it]?.notify) }

    /**
     * 예외를 영구 실패(재시도 불필요)인지 분류합니다.
     *
     * @param throwable 전달 실패 원인
     * @return 영구 실패면 true, 아니면 false
     */
    fun isPermanentFailure(throwable: Throwable): Boolean {
        when (throwable) {
            is io.github.kotlinsmtp.exception.SmtpSendResponse -> return throwable.statusCode in 500..599
            is RelayException -> return !throwable.isTransient
            is IllegalStateException -> {
                val message = throwable.message.orEmpty()
                return message.contains("No MX records", ignoreCase = true) ||
                    message.contains("No valid MX", ignoreCase = true)
            }
            is UnknownHostException -> return false
        }

        val returnCode = smtpReturnCodeOrNull(throwable)
        if (returnCode != null) return returnCode in 500..599

        val enhancedCode = enhancedCodeOrNull(throwable.message)
        if (enhancedCode != null) return enhancedCode.first() == '5'

        return false
    }

    /**
     * NOTIFY 파라미터를 토큰 집합으로 파싱합니다.
     *
     * @param notify RCPT 단위 NOTIFY 파라미터 원문
     * @return 파싱 결과 토큰 집합, 입력이 비어 있으면 null
     */
    private fun parseNotifyTokens(notify: String?): Set<String>? {
        val raw = notify?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return raw
            .split(',')
            .asSequence()
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun smtpReturnCodeOrNull(throwable: Throwable): Int? = runCatching {
        val method = throwable.javaClass.methods.firstOrNull {
            it.name == "getReturnCode" && it.parameterCount == 0
        } ?: return null
        method.invoke(throwable) as? Int
    }.getOrNull()

    private fun enhancedCodeOrNull(message: String?): String? {
        if (message.isNullOrBlank()) return null
        val match = ENHANCED_CODE_REGEX.find(message) ?: return null
        return match.groupValues.getOrNull(1)
    }
}

private val ENHANCED_CODE_REGEX = Regex("\\b(\\d\\.\\d\\.\\d)\\b")
