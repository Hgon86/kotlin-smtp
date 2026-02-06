## Thin Architecture (현재 코드베이스)

이 문서는 Kotlin SMTP 프로젝트의 현재 구조를 "얇게"(thin) 정리한 지도입니다.
라이브러리(core)로 발전시키는 과정에서 런타임 동작을 잃지 않기 위해, 흐름/경계/확장점을 최소한으로 기록합니다.

목표:
- 런타임 end-to-end 흐름(접속 -> 명령 처리 -> 메시지 수신 -> 저장/전달)을 요약
- 호스트가 교체 가능한 확장점(SPI)을 식별
- 범용 라이브러리 경계(core vs starter/host)를 명확히 함

범위 밖(Non-goals):
- RFC 전체 설명
- MIME 파싱/첨부파일 분리/인라인 이미지 처리 같은 "메일 서비스" 후처리
- 코드 재작성

### 런타임 흐름(정상 시나리오)

1) TCP accept
- `io.github.kotlinsmtp.server.SmtpServer` creates the Netty pipeline.
- Optional: PROXY protocol v1 (`HAProxyMessageDecoder`) if enabled.
- Optional: implicit TLS (SMTPS) via `SslHandler` if enabled.

2) Inbound framing
- `io.github.kotlinsmtp.server.SmtpInboundDecoder` turns the inbound stream into:
  - `SmtpInboundFrame.Line` (SMTP command lines)
  - `SmtpInboundFrame.Bytes` (BDAT chunks)

3) Session orchestration
- `io.github.kotlinsmtp.protocol.handler.SmtpChannelHandler` creates one `SmtpSession` per connection.
- Before session start (PROXY/implicit TLS gating), it buffers only a small number of line frames.
- After session start, it enqueues inbound frames directly into the session via `SmtpSession.tryEnqueueInboundFrame(...)`.
- Inbound flow-control is handled by `SmtpBackpressureController` (autoRead throttling) and inflight BDAT caps.

4) SMTP state machine
- `io.github.kotlinsmtp.server.SmtpSession.handle()`:
  - sends greeting (220)
  - reads lines sequentially
  - dispatches via `io.github.kotlinsmtp.protocol.command.api.SmtpCommands.handle(line, session)`

STARTTLS 업그레이드 흐름(중요)
- `StartTlsCommand` triggers a guarded upgrade:
  - pipelining is rejected
  - a temporary inbound gate buffers raw bytes until `SslHandler` is installed
  - handshake is awaited before session state is reset
  - implementation is isolated in `io.github.kotlinsmtp.server.SmtpTlsUpgradeManager`

5) Command layer
- Each command updates `SessionData` and/or calls into the transaction handler.
- DATA/BDAT eventually call `SmtpProtocolHandler.data(...)`.

6) Transaction handler (message acceptance)
- `SmtpSession.transactionHandler` is lazily created via `SmtpServer.transactionHandlerCreator`.
- Current default: `io.github.kotlinsmtp.protocol.handler.SimpleSmtpProtocolHandler`
  - stores raw message via `MessageStore.storeRfc822(...)`
  - either:
    - deliver synchronously, or
    - enqueue to spooler (`MailSpooler`) and return success to SMTP client

7) Delivery
- `io.github.kotlinsmtp.spool.MailDeliveryService`:
   - local: `LocalMailboxManager.deliverToLocalMailbox(...)`
   - external: `MailRelay.relay(RelayRequest)`
     - relay access policy는 `RelayAccessPolicy`로 분리되어 RCPT 단계에서 조기 거부(530/550)할 수 있습니다.
- `io.github.kotlinsmtp.spool.MailSpooler`는 retry/backoff를 제공하고,
  영구 실패 시(또는 max retry 초과 시) `DsnSender`가 제공되는 경우 DSN 발송을 시도합니다.

### 핵심 상태 객체

- `io.github.kotlinsmtp.model.SessionData`:
  - greeting state (EHLO/HELO)
  - AUTH state
  - TLS state
  - MAIL/RCPT and ESMTP parameters (SIZE/SMTPUTF8/DSN)

- `io.github.kotlinsmtp.server.SmtpSession`:
  - owns connection lifecycle
  - owns DATA/BDAT mode and buffering
  - provides `sendResponse(...)` and `sendMultilineResponse(...)`

### 확장점(현재)

현재 이미 존재하는 주요 경계(SPI 후보)입니다. 범용 라이브러리화를 위해 우선적으로 유지/안정화할 대상입니다.

- `io.github.kotlinsmtp.storage.MessageStore`
  - boundary for storing accepted raw RFC822 (usually .eml)
  - current impl: `FileMessageStore`

- `io.github.kotlinsmtp.auth.AuthService`
  - boundary for AUTH verification
  - current impl: `InMemoryAuthService`

- `io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler`
  - per-transaction handler (MAIL/RCPT/DATA)
  - current default: `SimpleSmtpProtocolHandler`

- `io.github.kotlinsmtp.protocol.handler.SmtpUserHandler`
  - VRFY-like user checks / local policy
  - current impl: `LocalDirectoryUserHandler`

- `io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler`
  - EXPN mailing list expansion
  - current impl: `LocalFileMailingListHandler`

- `io.github.kotlinsmtp.spi.SmtpEventHook`
  - minimal non-fatal event hooks for external integrations
  - examples: S3/Kafka/DB metadata publishing

### Spring 전용 레이어(와이어링만)

아래 클래스들은 Spring Boot에서 서버를 구성/실행하기 위한 와이어링 계층입니다.

- `io.github.kotlinsmtp.server.SmtpServerRunner` (start/stop on Spring lifecycle)
- `io.github.kotlinsmtp.config.KotlinSmtpAutoConfiguration` (auto-config wiring)
- `io.github.kotlinsmtp.config.SmtpServerProperties` (ConfigurationProperties)

Note: runnable example app(포트폴리오/데모)는 라이브러리 경계와 public API가 안정화된 뒤 마지막 단계에서 추가합니다.

중요: Spring-free `core`는 "서버를 실행할 수 없다"가 아닙니다.
- `core`는 SMTP 엔진 타입(SmtpServer/SmtpSession 등)과 확장점(SPI)을 제공합니다.
- host 모듈(Spring Boot starter 등)이 이를 구성하고 `server.start()`를 호출합니다.

### 범용 라이브러리 경계(현재 방향)

최소 경계(권장):

- `kotlin-smtp-core`
  - Netty SMTP engine + protocol state machine
  - public interfaces (MessageStore, AuthService, SmtpProtocolHandler, ...)
  - NO Spring dependencies

- host/통합 모듈(필요에 따라 선택):
  - `kotlin-smtp-spring-boot-starter` (auto-configuration + 기본 구현)
  - 옵션 모듈(예: S3/Kafka/DB 메타 저장 등)
  - `kotlin-smtp-example-app` (separate, last)

### 주의할 결합/리스크

- "저장/전달/후처리"가 기본 핸들러에 과도하게 섞이면 교체 비용이 커집니다.
  - 범용 라이브러리 목표에서는 core에 인프라 의존을 넣지 않고, starter/옵션 모듈로 분리하는 방향을 유지합니다.
- File-system defaults: Windows paths in config (`C:\smtp-server\...`) are host-specific.
- Mixed responsibilities in the default handler (`SimpleSmtpProtocolHandler` calls store + delivery + spool).
