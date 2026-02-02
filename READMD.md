# Kotlin SMTP Server

Kotlin 기반의 SMTP 서버 라이브러리 및 실행 애플리케이션입니다.
Netty 프레임워크를 기반으로 하며, 코루틴을 활용한 비동기 처리를 지원합니다.

## 프로젝트 구조

이 프로젝트는 라이브러리와 애플리케이션으로 분리된 구조입니다:

```
kotlin-smtp/
├── kotlin-smtp-core/          # Spring-free SMTP 엔진 라이브러리
│   ├── server/               # Netty 기반 SMTP 서버
│   ├── protocol/             # SMTP 프로토콜 구현
│   ├── auth/                 # 인증 인터페이스
│   └── storage/              # 메시지 저장 인터페이스
│
└── src/                      # Spring Boot 애플리케이션 (kotlin-smtp-app)
    ├── main/kotlin/
    │   └── io/github/kotlinsmtp/
    │       ├── config/       # Spring 설정
    │       └── ...           # 빈/와이어링
    └── main/resources/
        ├── application.yml              # 기본 설정
        
```

## 주요 기능

- **SMTP 프로토콜 구현**: RFC 5321 기반 SMTP 명령어 지원
- **TLS 보안 연결**: STARTTLS 및 Implicit TLS(SMTPS) 지원
- **AUTH 인증**: PLAIN 인증 구현
- **메일 릴레이**: MX 레코드 조회 및 외부 전달
- **로컬 메일박스**: 로컬 도메인 메일 저장
- **스풀 및 재시도**: 실패 메일 자동 재시도
- **Rate Limiting**: DoS 방지를 위한 연결/메시지 제한

## 빠른 시작

### 1. 필수 설정

`application.yml` 또는 환경변수로 저장소 경로를 설정해야 합니다:

```yaml
smtp:
  storage:
    mailboxDir: ./data/mailboxes
    tempDir: ./data/temp
    listsDir: ./data/lists
  spool:
    dir: ./data/spool
```

또는 환경변수 사용:
```bash
export SMTP_MAILBOX_DIR=./data/mailboxes
export SMTP_TEMP_DIR=./data/temp
export SMTP_LISTS_DIR=./data/lists
export SMTP_SPOOL_DIR=./data/spool
```

### 2. 실행

```bash
./gradlew bootRun
```

## 설정 가이드

### 기본 설정 (application.yml)

```yaml
smtp:
  port: 2525                    # 단일 포트 모드
  hostname: smtp.example.com
  
  # 또는 멀티 리스너 모드
  listeners:
    - port: 2525                # MTA 수신
      serviceName: ESMTP
      enableStartTls: true
      enableAuth: true
    - port: 2587                # Submission
      serviceName: SUBMISSION
      requireAuthForMail: true
    - port: 2465                # SMTPS
      serviceName: SMTPS
      implicitTls: true

  # 저장소 (필수)
  storage:
    mailboxDir: /var/smtp/mailboxes
    tempDir: /var/smtp/temp
    listsDir: /var/smtp/lists

  # TLS 설정
  ssl:
    enabled: true
    certChainFile: /etc/smtp/certs/tls.crt
    privateKeyFile: /etc/smtp/certs/tls.key

  # 릴레이 설정
  relay:
    enabled: true
    localDomain: example.com
    requireAuthForRelay: true   # 오픈 릴레이 방지
    outboundTls:
      trustAll: false           # 운영에서는 반드시 false

  # 스풀 설정
  spool:
    dir: /var/smtp/spool
    maxRetries: 5
    retryDelaySeconds: 60

  # 인증
  auth:
    enabled: true
    required: true
    users:
      user1: "{bcrypt}$2a$10$..."
```

### 환경변수 우선순위

모든 설정은 환경변수로 오버라이드 가능합니다:

| 환경변수 | 설명 | 예시 |
|---------|------|------|
| `SMTP_MAILBOX_DIR` | 메일박스 저장 경로 | `/var/smtp/mailboxes` |
| `SMTP_TEMP_DIR` | 임시 파일 경로 | `/var/smtp/temp` |
| `SMTP_SPOOL_DIR` | 스풀 디렉토리 | `/var/smtp/spool` |
| `SMTP_HOSTNAME` | 서버 호스트명 | `smtp.example.com` |
| `SMTP_TLS_ENABLED` | TLS 활성화 | `true` |
| `SMTP_RELAY_ENABLED` | 릴레이 활성화 | `false` |
| `SMTP_AUTH_ENABLED` | 인증 활성화 | `true` |

## 라이브러리 사용

`kotlin-smtp-core`를 의존성으로 사용하여 커스텀 SMTP 서버를 구축할 수 있습니다:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.kotlinsmtp:kotlin-smtp-core:VERSION")
}
```

```kotlin
import io.github.kotlinsmtp.server.SmtpServer
import io.github.kotlinsmtp.auth.AuthService
import io.github.kotlinsmtp.storage.MessageStore

val server = SmtpServer(
    port = 2525,
    hostname = "smtp.example.com",
    authService = myAuthService,
    transactionHandlerCreator = { myHandler() },
    messageStore = myMessageStore,
    // ... other options
)

server.start()
```

## 아키텍처

```
┌─────────────────────────────────────────────────────────┐
│  Network Layer (Netty)                                   │
│  ┌──────────────┐    ┌─────────────┐    ┌──────────┐   │
│  │ SmtpServer   │───▶│ Channel     │───▶│ Session  │   │
│  └──────────────┘    │ Handler     │    │          │   │
│                      └─────────────┘    └────┬─────┘   │
└───────────────────────────────────────────────┼─────────┘
                                                │
┌───────────────────────────────────────────────┼─────────┐
│  Protocol Layer                                │        │
│  ┌──────────────────┐    ┌────────────────────┘        │
│  │ SmtpCommands     │◄───┘                             │
│  │  ├── EHLO/HELO   │                                  │
│  │  ├── MAIL/RCPT   │                                  │
│  │  ├── DATA        │                                  │
│  │  ├── STARTTLS    │                                  │
│  │  └── AUTH        │                                  │
│  └──────────────────┘                                  │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  Handler Layer                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │ SmtpProtocolHandler (Transaction Handler)       │   │
│  │  ├── SimpleSmtpProtocolHandler (기본)           │   │
│  │  └── [Custom Handler] ← 사용자 구현 가능        │   │
│  └─────────────────────────────────────────────────┘   │
│                         │                              │
│  ┌──────────────┐  ┌────┴─────┐  ┌─────────────┐      │
│  │ MessageStore │  │ Delivery │  │ AuthService │      │
│  │ (Storage)    │  │ Service  │  │ (Auth)      │      │
│  └──────────────┘  └──────────┘  └─────────────┘      │
└─────────────────────────────────────────────────────────┘
```

## 보안 가이드

1. **오픈 릴레이 방지**: `smtp.relay.requireAuthForRelay=true` 설정
2. **TLS 강제**: `trustAll=false` (운영 환경에서만)
3. **Rate Limiting**: IP당 연결/메시지 수 제한
4. **PROXY Protocol**: LB 뒤에서만 사용, trustedCidrs 설정 필수

## 라이선스

[라이선스 정보 추가 필요]

---

## 문서

- [CORE_EXTRACTION_PLAN.md](docs/CORE_EXTRACTION_PLAN.md) - Core 모듈 분리 계획
- [THIN_ARCHITECTURE.md](docs/THIN_ARCHITECTURE.md) - 현재 아키텍처 문서
- [ROADMAP.md](docs/ROADMAP.md) - 프로젝트 로드맵 및 작업 추적
- [PUBLIC_API_CANDIDATES.md](docs/PUBLIC_API_CANDIDATES.md) - 공개 API 후보
