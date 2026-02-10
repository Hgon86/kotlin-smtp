package io.github.kotlinsmtp.server

/**
 * 서버 기능 플래그 모음입니다.
 *
 * @property enableVrfy VRFY 커맨드 활성화
 * @property enableEtrn ETRN 커맨드 활성화
 * @property enableExpn EXPN 커맨드 활성화
 */
public class SmtpFeatureFlags {
    public var enableVrfy: Boolean = false
    public var enableEtrn: Boolean = false
    public var enableExpn: Boolean = false
}

/**
 * 리스너(포트)별 정책입니다.
 *
 * @property implicitTls 접속 즉시 TLS 시작 여부 (SMTPS/465)
 * @property enableStartTls STARTTLS 지원 여부
 * @property enableAuth AUTH 커맨드/광고 허용 여부
 * @property requireAuthForMail MAIL 트랜잭션 시작 전 AUTH 강제 여부
 * @property idleTimeoutSeconds 연결 유휴 타임아웃(초). 0이면 타임아웃 없음 (기본: 300초=5분)
 */
public class SmtpListenerPolicy {
    public var implicitTls: Boolean = false
    public var enableStartTls: Boolean = true
    public var enableAuth: Boolean = true
    public var requireAuthForMail: Boolean = false
    public var idleTimeoutSeconds: Int = 300
}

/**
 * PROXY protocol(v1) 설정입니다.
 *
 * @property enabled PROXY protocol 수신 여부
 * @property trustedProxyCidrs 신뢰 프록시 CIDR 목록
 */
public class SmtpProxyProtocolPolicy {
    public var enabled: Boolean = false
    public var trustedProxyCidrs: List<String> = listOf("127.0.0.1/32", "::1/128")
}

/**
 * TLS 설정입니다.
 *
 * @property certChainPath 인증서 체인 경로
 * @property privateKeyPath 개인키 경로
 * @property minTlsVersion 최소 TLS 버전
 * @property handshakeTimeoutMs TLS 핸드셰이크 타임아웃(ms)
 * @property cipherSuites 허용 cipher suites(지정 시)
 */
public class SmtpTlsPolicy {
    public var certChainPath: java.nio.file.Path? = null
    public var privateKeyPath: java.nio.file.Path? = null
    public var minTlsVersion: String = "TLSv1.2"
    public var handshakeTimeoutMs: Int = 30_000
    public var cipherSuites: List<String> = emptyList()
}

/**
 * 연결/메시지 Rate Limit 설정입니다.
 *
 * @property maxConnectionsPerIp IP당 최대 동시 연결 수
 * @property maxMessagesPerIpPerHour IP당 시간당 최대 메시지 수
 */
public class SmtpRateLimitPolicy {
    public var maxConnectionsPerIp: Int = 10
    public var maxMessagesPerIpPerHour: Int = 100
}

/**
 * 인증(AUTH) Rate Limit 설정입니다.
 *
 * @property enabled 인증 rate limit 사용 여부
 * @property maxFailuresPerWindow 윈도우 내 최대 실패 횟수
 * @property windowSeconds 윈도우 크기(초)
 * @property lockoutDurationSeconds 잠금 지속 시간(초)
 */
public class SmtpAuthRateLimitPolicy {
    public var enabled: Boolean = true
    public var maxFailuresPerWindow: Int = 5
    public var windowSeconds: Long = 300
    public var lockoutDurationSeconds: Long = 600
}
