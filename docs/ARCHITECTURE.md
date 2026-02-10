# 아키텍처 개요

## 시스템 구조

Kotlin SMTP는 모듈화된 아키텍처로 설계되었습니다.

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ SMTP Server  │  │   Mailbox    │  │    Spool     │     │
│  │   (Core)     │  │   Store      │  │   Handler    │     │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
└─────────┼────────────────┼────────────────┼───────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                     Core Engine                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │   Protocol   │  │   Session    │  │    Auth      │     │
│  │   Handler    │  │   Manager    │  │   Service    │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │   Netty      │  │   TLS/SSL    │  │   Framing    │     │
│  │   Pipeline   │  │   Handler    │  │   (DATA/BDAT)│     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

## 모듈 설명

### 1. kotlin-smtp-core

SMTP 프로토콜 엔진. Spring에 의존하지 않습니다.

**주요 컴포넌트:**

- **SmtpServer**: Netty 기반 SMTP 서버
- **SmtpSession**: 클라이언트 세션 관리
- **Protocol Handler**: SMTP 명령 처리 (EHLO, MAIL, RCPT, DATA 등)
- **Framing**: DATA/BDAT 청크 처리
- **TLS Handler**: STARTTLS/Implicit TLS 지원

### 2. kotlin-smtp-spring-boot-starter

Spring Boot 자동 설정 및 기본 구현체 제공.

**제공 기능:**

- 자동 설정 (Auto-configuration)
- 파일 기반 MessageStore
- 파일 기반 Spooler
- 인메모리 AuthService
- 로컬 메일박스 관리

### 3. kotlin-smtp-relay* (옵션)

외부 도메인으로의 메일 릴레이 기능.

**구성:**
- **relay**: Public API (MailRelay, DsnSender 등)
- **relay-jakarta-mail**: Jakarta Mail 기반 구현체
- **relay-spring-boot-starter**: Spring Boot 통합

## 데이터 흐름

### 메일 수신 프로세스

```
1. TCP Connection Accept
   ↓
2. SMTP Handshake (EHLO/HELO)
   ↓
3. Authentication (Optional - AUTH)
   ↓
4. Envelope (MAIL FROM, RCPT TO)
   ↓
5. Message Transfer (DATA/BDAT)
   ↓
6. Storage → MessageStore.storeRfc822()
   ↓
7. Delivery Decision
   ├─ Local Domain → Local Mailbox
   └─ External Domain → Spooler (for relay)
```

### 스풀/재시도 프로세스

```
Spool Directory
   ↓
1. Scheduled Scan (retryDelaySeconds 간격)
   ↓
2. Load Metadata (nextAttemptAt 확인)
   ↓
3. Delivery Attempt
   ├─ Success → Remove from spool
   ├─ Transient Failure → Schedule Retry (exponential backoff)
   └─ Permanent Failure → DSN + Remove
```

## 확장 포인트

### SPI (Service Provider Interface)

코어 모듈은 다음 인터페이스를 제공합니다:

1. **MessageStore**: 메시지 저장소
   ```kotlin
   interface MessageStore {
       suspend fun storeRfc822(
           sender: String,
           recipients: List<String>,
           data: InputStream,
           size: Long
       ): StoredMessage
   }
   ```

2. **AuthService**: 인증 서비스
   ```kotlin
   interface AuthService {
       suspend fun authenticate(credentials: Credentials): AuthResult
   }
   ```

3. **SmtpProtocolHandler**: 트랜잭션 핸들러
   ```kotlin
   interface SmtpProtocolHandler {
       suspend fun from(sender: String)
       suspend fun to(recipient: String)
       suspend fun data(inputStream: InputStream, size: Long)
   }
   ```

4. **SmtpEventHook**: 이벤트 훅
   ```kotlin
   interface SmtpEventHook {
       fun onSessionStarted(event: SmtpSessionStartedEvent)
       fun onMessageAccepted(event: SmtpMessageAcceptedEvent)
   }
   ```

## 저장소 구현체

### 파일 기반 (기본)

```yaml
smtp:
  storage:
    mailboxDir: ./data/mailboxes  # 로컬 메일박스
    tempDir: ./data/temp          # 임시 파일
  spool:
    dir: ./data/spool             # 재시도 큐
```

### DB 기반 (직접 구현)

DB 저장소를 사용하려면:

1. `MessageStore` 인터페이스 구현
2. Spring Bean으로 등록
3. 기본 `FileMessageStore` 대체

자세한 내용은 [EXTENSION.md](EXTENSION.md) 참조.

## 보안

### 기본 보안 기능

- **Rate Limiting**: IP 기반 연결/메시지 제한
- **TLS**: STARTTLS 및 Implicit TLS 지원
- **Auth Rate Limiting**: AUTH 브루트포스 방지
- **PROXY Protocol**: 신뢰할 수 있는 프록시만 수용

### 오픈 릴레이 방지

릴레이 모듈 사용 시:
- 기본적으로 `requireAuthForRelay: true`
- `allowedSenderDomains`로 특정 발신 도메인만 허용 가능
- 인증 없이는 외부 도메인으로 릴레이 불가

## 성능

### 백프레셔 (Backpressure)

- **Soft Throttling**: queued bytes 기준 autoRead 토글
- **BDAT Inflight Cap**: 청크 단위 메모리 제한
- **Connection Limit**: IP당 최대 연결 수 제한

### 비동기 처리

- Netty 기반 논블로킹 I/O
- 코루틴 기반 비동기 처리
- 스풀러 비동기 배달 시도
