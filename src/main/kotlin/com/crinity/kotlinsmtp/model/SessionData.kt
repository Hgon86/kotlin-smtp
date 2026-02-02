package com.crinity.kotlinsmtp.model

class SessionData {
    // 클라이언트 식별 (HELO/EHLO 인자)
    var helo: String? = null; internal set

    // 상태 머신
    var greeted: Boolean = false; internal set // HELO/EHLO 수행 여부
    var usedEhlo: Boolean = false; internal set // EHLO 사용 여부
    var mailFrom: String? = null; internal set // MAIL FROM 주소
    var recipientCount: Int = 0; internal set // RCPT 수

    // ESMTP 파라미터 (MAIL FROM에서 선언된 값들)
    var mailParameters: Map<String, String> = emptyMap(); internal set // MAIL FROM 파라미터
    var declaredSize: Long? = null; internal set // SIZE 파라미터 값

    // RFC 6531 (SMTPUTF8)
    // - MAIL FROM 단계에서 SMTPUTF8 파라미터가 선언되면, 이 트랜잭션은 UTF-8 주소/헤더를 포함할 수 있습니다.
    // - 지금은 "주소 수용 + 로컬/릴레이 경로에서 보존"까지를 최소 구현으로 둡니다.
    var smtpUtf8: Boolean = false; internal set

    // RFC 3461(DSN) - MAIL FROM 확장
    // - RET=FULL|HDRS, ENVID=<id>
    var dsnRet: String? = null; internal set
    var dsnEnvid: String? = null; internal set

    // RFC 3461(DSN) - RCPT TO 확장(수신자별)
    // - NOTIFY=..., ORCPT=...
    // TODO(표준 DSN): RFC 3464 생성 시 각 수신자의 옵션을 반영
    var rcptDsn: MutableMap<String, RcptDsn> = mutableMapOf(); internal set

    // 연결 컨텍스트
    var peerAddress: String? = null; internal set // 클라이언트 IP:port
    var serverHostname: String? = null; internal set // 서버 호스트명
    var tlsActive: Boolean = false; internal set // TLS 사용 여부

    // AUTH 상태
    var authFailedAttempts: Int? = null; internal set
    var authLockedUntilEpochMs: Long? = null; internal set
    var isAuthenticated: Boolean = false; internal set
}