package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.model.SessionData
import java.io.InputStream

/**
 * SMTP 트랜잭션 처리 훅을 제공하는 핸들러입니다.
 *
 * - [io.github.kotlinsmtp.server.SmtpServerBuilder.useProtocolHandlerFactory]를 통해 등록합니다.
 * - [sessionData]는 엔진이 초기화하며, 생성자/초기화 블록에서 접근하면 안 됩니다.
 */
public abstract class SmtpProtocolHandler {
    public lateinit var sessionData: SessionData
        internal set

    internal fun init(sessionData: SessionData) {
        this.sessionData = sessionData
    }

    /**
     * MAIL FROM 수신 시 호출됩니다.
     *
     * @param sender 발신자 주소(정규화/검증 후 값)
     */
    public open suspend fun from(sender: String): Unit {}

    /**
     * RCPT TO 수신 시 호출됩니다.
     *
     * @param recipient 수신자 주소(정규화/검증 후 값)
     */
    public open suspend fun to(recipient: String): Unit {}

    /**
     * DATA/BDAT 본문 수신 시 호출됩니다.
     *
     * @param inputStream 메시지 원문 스트림(소비는 구현체 책임)
     * @param size 메시지 크기(bytes)
     */
    public open suspend fun data(inputStream: InputStream, size: Long): Unit {}

    /**
     * 한 트랜잭션이 완료될 때 호출됩니다.
     *
     * - 정상 완료(예: "." 수신) 또는 RSET 등으로 종료되는 경우를 포함할 수 있습니다.
     */
    public open suspend fun done(): Unit {}
}
