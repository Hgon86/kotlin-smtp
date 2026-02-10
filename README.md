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
    dir: ./data/spool
    maxRetries: 5
    retryDelaySeconds: 60
```

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

## 로컬 배포 검증

```bash
./gradlew publishToMavenLocal
```

## Example App 실행

```bash
./gradlew :kotlin-smtp-example-app:bootRun
```

## CI / 배포 자동화

- CI: `.github/workflows/ci.yml`
  - PR/Push에서 Linux/Windows `./gradlew test`
- Publish: `.github/workflows/publish.yml`
  - 태그(`v*`) 또는 수동 실행으로 `./gradlew publish`

필요 시 GitHub Secrets:
- `OSSRH_USERNAME`, `OSSRH_PASSWORD`
- `SIGNING_KEY`, `SIGNING_PASSWORD`

## Maven Central 준비 상태

루트 빌드에서 publishable 모듈에 공통 설정을 적용합니다.
- `maven-publish` + `signing`
- `sourcesJar` / `javadocJar`
- POM 메타데이터(license/scm/developers)
- SNAPSHOT/RELEASE 저장소 분기(OSSRH)

## 문서

- `docs/STATUS.md`: 현재 진행 상황
- `docs/ROADMAP.md`: 남은 작업 우선순위
- `docs/THIN_ARCHITECTURE.md`: 런타임/경계 요약
- `docs/PUBLIC_API_CANDIDATES.md`: Public API 경계
- `docs/RELAY_MODULES.md`: relay 모듈 설계/정책

## 라이선스

Apache License 2.0 (`LICENSE`)
