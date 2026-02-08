## Public API Candidates (Library Mode)

This file lists the intended semver-stable API surface for publishing `kotlin-smtp-core` as a reusable library.

Rule of thumb:
- If a host application must **call / implement / reference** a type to customize behavior, it belongs in the public API.
- Everything else remains internal or implementation detail.

Last synced with code: 2026-02-08

---

### Semver-Stable Packages (Target)

We treat only the following packages as semver-stable API:

- `io.github.kotlinsmtp.server` (selected types only)
- `io.github.kotlinsmtp.model`
- `io.github.kotlinsmtp.exception`
- `io.github.kotlinsmtp.storage`
- `io.github.kotlinsmtp.auth`
- `io.github.kotlinsmtp.protocol.handler`
- `io.github.kotlinsmtp.spi`

---

### Core Engine (Public)

#### Entry Points

- `io.github.kotlinsmtp.server.SmtpServer`
  - **Preferred entrypoints:**
    - `SmtpServer.create(port, hostname) { }` - Kotlin DSL style
    - `SmtpServer.builder(port, hostname)` - Java interop / programmatic
  - **Lifecycle:** `suspend fun start(wait: Boolean = false): Boolean`, `suspend fun stop(gracefulTimeoutMs: Long = 30000): Boolean`
  - **Implementation constructor:** `internal` (use builder/create only)

#### Server Configuration (Builder API)

All nested classes under `SmtpServer.Builder`:

- `SmtpServer.FeatureFlags` - VRFY/ETRN/EXPN 활성화
- `SmtpServer.ListenerPolicy` - STARTTLS/AUTH/implicit TLS 정책
- `SmtpServer.ProxyProtocolPolicy` - PROXY protocol 설정
- `SmtpServer.TlsPolicy` - 인증서/TLS 버전 설정
- `SmtpServer.RateLimitPolicy` - 연결/메시지 rate limit
- `SmtpServer.AuthRateLimitPolicy` - 인증 rate limit

#### Spooler Hooks

- `io.github.kotlinsmtp.server.SmtpSpooler`
  - **Purpose:** 스풀/딜리버리 처리 트리거
  - **Method:** `fun triggerOnce(): Unit`
  
- `io.github.kotlinsmtp.server.SmtpDomainSpooler`
  - **Extends:** `SmtpSpooler`
  - **Purpose:** ETRN 도메인 인자 지원
  - **Method:** `fun triggerOnce(domain: String): Unit`

---

### Core Models (Public)

- `io.github.kotlinsmtp.model.SessionData`
  - **Purpose:** 세션/트랜잭션 상태 데이터 (read-mostly)
  - **Properties:** `helo`, `mailFrom`, `tlsActive`, `isAuthenticated`, `rcptDsnView`, etc.
  - **Note:** 가변 객체는 `internal set`으로 보호됨

- `io.github.kotlinsmtp.model.SmtpUser`
  - **Purpose:** VRFY 결과로 반환되는 사용자 정보
  - **Properties:** `username`, `email`

- `io.github.kotlinsmtp.model.RcptDsn`
  - **Purpose:** RFC 3461 DSN 파라미터 (수신자별)
  - **Properties:** `notify`, `orcpt`

---

### Core Exceptions (Public)

- `io.github.kotlinsmtp.exception.SmtpSendResponse`
  - **Purpose:** 특정 SMTP 응답 코드/메시지 반환용 예외
  - **Usage:** Handler에서 throw하면 해당 코드로 응답
  - **Code Reference:** `SmtpStatusCode` ( companion constants)

---

### Extension Interfaces (Public)

#### Storage

- `io.github.kotlinsmtp.storage.MessageStore`
  - **Purpose:** 수신 메시지 원문 저장
  - **Method:** `suspend fun storeRfc822(messageId, receivedHeaderValue, rawInput): Path`

#### Authentication

- `io.github.kotlinsmtp.auth.AuthService`
  - **Purpose:** 인증 서비스 구현
  - **Properties:** `enabled: Boolean`, `required: Boolean`
  - **Method:** `fun verify(username, password): Boolean`

#### Protocol Handlers

- `io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler`
  - **Purpose:** SMTP 트랜잭션 처리
  - **Lifecycle:** `from()`, `to()`, `data()`, `done()`
  - **Note:** `sessionData`는 엔진이 초기화

- `io.github.kotlinsmtp.protocol.handler.SmtpUserHandler`
  - **Purpose:** VRFY 명령어 처리
  - **Method:** `fun verify(searchTerm): Collection<SmtpUser>`

- `io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler`
  - **Purpose:** EXPN 명령어 처리
  - **Method:** `fun expand(listName): List<String>`

---

### SPI Hooks (Public)

- `io.github.kotlinsmtp.spi.SmtpEventHook`
  - **Purpose:** Non-fatal 이벤트 훅 (S3/Kafka/DB 연동용)
  - **Methods:**
    - `onSessionStarted(SmtpSessionStartedEvent)`
    - `onSessionEnded(SmtpSessionEndedEvent)`
    - `onMessageAccepted(SmtpMessageAcceptedEvent)`
    - `onMessageRejected(SmtpMessageRejectedEvent)`

#### SPI Event Types

- `io.github.kotlinsmtp.spi.SmtpSessionContext` - 세션 식별/환경 정보
- `io.github.kotlinsmtp.spi.SmtpMessageEnvelope` - 메시지 엔벨로프
- `io.github.kotlinsmtp.spi.SmtpMessageTransferMode` - DATA/BDAT
- `io.github.kotlinsmtp.spi.SmtpMessageStage` - RECEIVING/PROCESSING
- `io.github.kotlinsmtp.spi.SmtpSessionEndReason` - 종료 사유
- Event data classes:
  - `SmtpSessionStartedEvent`
  - `SmtpSessionEndedEvent`
  - `SmtpMessageAcceptedEvent`
  - `SmtpMessageRejectedEvent`

---

### Not Public / Internal (Guaranteed)

The following are implementation details and subject to change without notice:

#### Netty Pipeline & Framing

- `io.github.kotlinsmtp.protocol.handler.SmtpChannelHandler` - Netty ChannelHandler
- `io.github.kotlinsmtp.server.SmtpInboundDecoder` - SMTP 프레이밍 디코더
- `io.github.kotlinsmtp.server.SmtpInboundFrame` - 디코딩 결과 프레임
- `io.github.kotlinsmtp.server.SmtpTlsUpgradeManager` - STARTTLS 업그레이드 관리
- `io.github.kotlinsmtp.server.StartTlsInboundGate` - 업그레이드 시 입력 게이트
- `io.github.kotlinsmtp.server.SmtpBackpressureController` - 백프레셔 제어
- `io.github.kotlinsmtp.server.BdatStreamingState` - BDAT 스트리밍 상태
- `io.github.kotlinsmtp.server.SmtpStreamingHandlerRunner` - DATA/BDAT 공통 실행기
- `io.github.kotlinsmtp.server.SmtpSessionDataResetter` - 트랜잭션 리셋

#### Protocol Command Implementations

- `io.github.kotlinsmtp.protocol.command.*` - 모든 명령어 구현체
  - `AuthCommand`, `MailCommand`, `RcptCommand`, `DataCommand`, `BdatCommand`, etc.
- `io.github.kotlinsmtp.protocol.command.api.*` - 명령어 인터페이스 (internal)

#### Utilities & Helpers

- `io.github.kotlinsmtp.utils.*` - 모든 유틸리티 클래스
  - `SmtpStatusCode`, `CommandParsers`, `IpCidr`, `AddressUtils`, etc.

#### Server Internals

- `io.github.kotlinsmtp.server.RateLimiter` - Rate limiting 구현
- `io.github.kotlinsmtp.server.ActiveSessionTracker` - 세션 추적
- `io.github.kotlinsmtp.server.SmtpSession` - 세션 상태 머신 (낮은 수준)
- `io.github.kotlinsmtp.auth.AuthRateLimiter` - 인증 rate limit 구현
- `io.github.kotlinsmtp.auth.SaslPlain` - SASL PLAIN 디코딩 (internal)

---

### Note: Implementations Live Outside Core

The core module is infrastructure-agnostic.

Concrete implementations such as file-based storage, local mailbox management, or outbound relay are expected to live in:

- `kotlin-smtp-spring-boot-starter` - convenience wiring + local default implementations
- `kotlin-smtp-relay*` - optional modules for outbound relay + policy + DSN

---

### API Evolution Policy

See `PUBLIC_API_POLICY.md` for:
- Semver versioning rules
- Deprecation policy
- Breaking change guidelines
- Internal API exposure rules
