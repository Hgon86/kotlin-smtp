# Kotlin SMTP

Netty 기반 Kotlin SMTP 서버 라이브러리입니다. `core`는 Spring 없이 동작하는 SMTP 엔진을 제공하고,
`starter` 계열 모듈은 Spring Boot에서 바로 실행 가능한 구성을 제공합니다.

## 모듈 구성

```text
kotlin-smtp/
├── kotlin-smtp-core                         # Spring-free SMTP 엔진
├── kotlin-smtp-spring-boot-starter          # inbound 중심 starter(auto-config + 기본 구현)
├── kotlin-smtp-relay                        # outbound relay API 경계
├── kotlin-smtp-relay-jakarta-mail           # relay 구현체(dnsjava + jakarta-mail)
├── kotlin-smtp-relay-spring-boot-starter    # relay auto-config
└── kotlin-smtp-example-app                  # 소비 예제 앱
```

현재 구조는 의도된 분리입니다.
- `core`: 프로토콜/세션/TLS/AUTH/프레이밍 정확성
- `starter`: 빠른 기동 경험 + 기본 파일 기반 구현
- `relay*`: 외부 전달(outbound) 경계를 옵션 모듈로 분리

## 핵심 기능

- RFC 5321 기본 명령: `EHLO/HELO`, `MAIL`, `RCPT`, `DATA`, `RSET`, `QUIT`
- `BDAT`(Chunking), `STARTTLS`, `AUTH PLAIN`
- SMTPUTF8/IDN 경계 처리
- PROXY protocol(v1), rate limit
- ETRN/VRFY/EXPN(기능 플래그)
- 스풀/재시도/DSN(RFC 3464) 처리

## 빠른 시작 (Starter)

### 1) 의존성

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("io.github.hgon86:kotlin-smtp-spring-boot-starter:VERSION")
}
```

### 2) 최소 설정

```yaml
smtp:
  port: 2525
  hostname: localhost
  routing:
    localDomain: local.test
  storage:
    mailboxDir: ./data/mailboxes
    tempDir: ./data/temp
    listsDir: ./data/lists
  spool:
    type: auto # auto | file | redis
    dir: ./data/spool
    maxRetries: 5
    retryDelaySeconds: 60
  sentArchive:
    mode: TRUSTED_SUBMISSION # TRUSTED_SUBMISSION | AUTHENTICATED_ONLY | DISABLED
```

Redis 스풀 백엔드를 사용하려면:

```yaml
smtp:
  spool:
    type: redis
    dir: ./data/spool
    redis:
      keyPrefix: kotlin-smtp:spool
      maxRawBytes: 26214400
      lockTtlSeconds: 900
```

- `type=auto`는 `StringRedisTemplate` 빈이 있으면 Redis, 없으면 file을 자동 선택합니다.
- `type=redis`일 때 큐/락/메타데이터는 Redis에 저장됩니다.
- 원문 `.eml`도 Redis에 저장됩니다(지속 파일 저장 없음).
- 배달 시점에만 임시 파일을 생성해 사용 후 즉시 정리합니다.
- 애플리케이션에 `StringRedisTemplate` 빈이 있어야 합니다.
- Redis 단일/클러스터/Sentinel 구성은 애플리케이션 측 설정을 그대로 따릅니다.

기본 구현에서는 `mailboxDir/<owner>/sent/` 경로에 보낸 메일함 사본을 저장합니다.
- 인증 세션은 AUTH 사용자(`authenticatedUsername`)를 소유자로 사용합니다.
- 무인증 제출은 envelope sender local-part를 소유자로 사용합니다.
사용자가 `SentMessageStore` 빈을 직접 등록하면 S3/DB+ObjectStorage 등 원하는 방식으로 교체할 수 있습니다.

보낸 메일함 저장 기준은 `smtp.sentArchive.mode`로 제어합니다.
- `TRUSTED_SUBMISSION`(기본): AUTH 인증 세션 또는 외부 릴레이 제출 메시지 저장
- `AUTHENTICATED_ONLY`: AUTH 인증 세션만 저장
- `DISABLED`: 저장 안 함

릴레이 무인증 제출을 IP 기준으로 제한하려면 `smtp.relay.allowedClientCidrs`를 사용하세요.
더 복잡한 기준(DB 조회/사내 정책 엔진)이 필요하면 `RelayAccessPolicy` 빈을 커스텀 구현해 교체할 수 있습니다.

전체 예시는 `docs/application.example.yml`를 참고하세요.

## Core 단독 사용

```kotlin
import io.github.kotlinsmtp.server.SmtpServer

val server = SmtpServer.create(2525, "smtp.example.com") {
    serviceName = "example-smtp"
    listener.enableStartTls = true
    listener.enableAuth = false
}

server.start()
```

## 관측성(Observability)

Micrometer 연동은 **SMTP 포트에 엔드포인트를 추가하지 않습니다**.
- SMTP는 기존 포트(예: 2525) 그대로 동작
- 메트릭 노출은 Spring Actuator 관리 채널(옵트인)에서만 수행

기본 계측 항목:
- `smtp.connections.active`
- `smtp.sessions.started.total`, `smtp.sessions.ended.total`
- `smtp.messages.accepted.total`, `smtp.messages.rejected.total`
- `smtp.spool.pending`, `smtp.spool.queued.total`, `smtp.spool.completed.total`
- `smtp.spool.dropped.total`, `smtp.spool.retry.scheduled.total`
- `smtp.spool.delivery.recipients.total{result=delivered|transient_failure|permanent_failure}`

Prometheus 노출 예시(옵션):

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
}
```

```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

이 경우 `/actuator/prometheus`가 **관리 포트**에서만 열립니다.

## Example App 실행

```bash
./gradlew :kotlin-smtp-example-app:bootRun
```

## 문서

- `docs/STATUS.md`: 현재 진행 상황
- `docs/ROADMAP.md`: 남은 작업 우선순위
- `docs/THIN_ARCHITECTURE.md`: 런타임/경계 요약
- `docs/PUBLIC_API_CANDIDATES.md`: Public API 경계
- `docs/RELAY_MODULES.md`: relay 모듈 설계/정책

## 라이선스

Apache License 2.0 (`LICENSE`)
