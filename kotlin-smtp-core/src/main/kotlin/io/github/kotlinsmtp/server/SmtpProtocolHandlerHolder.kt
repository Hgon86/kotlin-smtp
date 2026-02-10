package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.model.SessionData
import io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler

/**
 * 세션의 트랜잭션 핸들러 생명주기를 관리합니다.
 *
 * @property creator 핸들러 생성 팩토리
 */
internal class SmtpProtocolHandlerHolder(
    private val creator: (() -> SmtpProtocolHandler)?,
) {
    @Volatile
    private var current: SmtpProtocolHandler? = null

    /**
     * 필요 시 핸들러를 생성하고 반환합니다.
     *
     * @param sessionData 핸들러 초기화에 사용할 세션 데이터
     * @return 현재 핸들러 또는 생성된 핸들러
     */
    fun getOrCreate(sessionData: SessionData): SmtpProtocolHandler? {
        val existing = current
        if (existing != null) return existing

        val factory = creator ?: return null
        return synchronized(this) {
            current
                ?: factory.invoke().also {
                    it.init(sessionData)
                    current = it
                }
        }
    }

    /**
     * 핸들러를 종료하고 참조를 해제합니다.
     */
    suspend fun doneAndClear() {
        val handler = synchronized(this) {
            val value = current
            current = null
            value
        }
        handler?.done()
    }
}
