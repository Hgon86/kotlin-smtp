package io.github.kotlinsmtp.relay.api

/**
 * 수신자/요청 정보에 따라 아웃바운드 릴레이 경로를 결정하는 SPI.
 *
 * 기본 구현은 설정 파일(application.yml) 기반으로 동작하며,
 * 사용자는 DB/설정 서버 조회 구현으로 교체할 수 있습니다.
 */
public fun interface RelayRouteResolver {
    /**
     * 주어진 릴레이 요청의 전송 경로를 반환합니다.
     *
     * @param request 릴레이 입력 컨텍스트
     * @return 선택된 릴레이 경로
     */
    public fun resolve(request: RelayRequest): RelayRoute
}

/**
 * 아웃바운드 릴레이 경로 모델.
 */
public sealed interface RelayRoute {
    /** MX 조회 기반 직접 전송 경로. */
    public data object DirectMx : RelayRoute

    /**
     * 지정 SMTP 서버(Smart Host) 전송 경로.
     *
     * @property host 대상 SMTP 호스트
     * @property port 대상 SMTP 포트
     * @property username SMTP AUTH 사용자명(선택)
     * @property password SMTP AUTH 비밀번호(선택)
     * @property startTlsEnabled STARTTLS 시도 여부(선택)
     * @property startTlsRequired STARTTLS 필수 여부(선택)
     * @property checkServerIdentity 서버 인증서 호스트명 검증 여부(선택)
     * @property trustAll 개발/테스트용 trust-all 여부(선택)
     * @property trustHosts 허용할 신뢰 호스트 목록(선택)
     */
    public data class SmartHost(
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
        val startTlsEnabled: Boolean? = null,
        val startTlsRequired: Boolean? = null,
        val checkServerIdentity: Boolean? = null,
        val trustAll: Boolean? = null,
        val trustHosts: List<String>? = null,
    ) : RelayRoute
}
