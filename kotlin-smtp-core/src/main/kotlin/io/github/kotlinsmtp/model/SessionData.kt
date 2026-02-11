package io.github.kotlinsmtp.model

/**
 * SMTP 세션(연결/트랜잭션) 처리 중 엔진이 축적하는 상태 데이터입니다.
 *
 * - 엔진이 상태 머신을 관리하며, 외부(호스트)에서는 읽기 위주로 사용합니다.
 * - 컬렉션 등 가변 객체는 외부에서 변이되지 않도록 읽기 전용 뷰만 제공합니다.
 *
 * @property rcptDsnView 수신자별 DSN(RFC 3461) 파라미터의 읽기 전용 뷰
 */
public class SessionData {
    // 클라이언트 식별 (HELO/EHLO 인자)
    public var helo: String? = null; internal set

    // 상태 머신
    public var greeted: Boolean = false; internal set // HELO/EHLO 수행 여부
    public var usedEhlo: Boolean = false; internal set // EHLO 사용 여부
    public var mailFrom: String? = null; internal set // MAIL FROM 주소
    public var recipientCount: Int = 0; internal set // RCPT 수

    // ESMTP 파라미터 (MAIL FROM에서 선언된 값들)
    public var mailParameters: Map<String, String> = emptyMap(); internal set // MAIL FROM 파라미터
    public var declaredSize: Long? = null; internal set // SIZE 파라미터 값

    // RFC 6531 (SMTPUTF8)
    // - MAIL FROM 단계에서 SMTPUTF8 파라미터가 선언되면, 이 트랜잭션은 UTF-8 주소/헤더를 포함할 수 있습니다.
    // - 지금은 "주소 수용 + 로컬/릴레이 경로에서 보존"까지를 최소 구현으로 둡니다.
    public var smtpUtf8: Boolean = false; internal set

    // RFC 3461(DSN) - MAIL FROM 확장
    // - RET=FULL|HDRS, ENVID=<id>
    public var dsnRet: String? = null; internal set
    public var dsnEnvid: String? = null; internal set

    // RFC 3461(DSN) - RCPT TO 확장(수신자별)
    // - NOTIFY=..., ORCPT=...
    // TODO(표준 DSN): RFC 3464 생성 시 각 수신자의 옵션을 반영
    internal var rcptDsn: MutableMap<String, RcptDsn> = mutableMapOf()

    /** 외부에서 DSN 상태를 변이하지 않도록 읽기 전용 뷰만 제공합니다. */
    public val rcptDsnView: Map<String, RcptDsn>
        get() = java.util.Collections.unmodifiableMap(rcptDsn)

    // 연결 컨텍스트
    public var peerAddress: String? = null; internal set // 클라이언트 IP:port
    public var serverHostname: String? = null; internal set // 서버 호스트명
    public var tlsActive: Boolean = false; internal set // TLS 사용 여부

    // AUTH 상태
    public var authFailedAttempts: Int? = null; internal set
    public var authLockedUntilEpochMs: Long? = null; internal set
    public var isAuthenticated: Boolean = false; internal set
    public var authenticatedUsername: String? = null; internal set
}
