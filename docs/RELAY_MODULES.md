# Relay Modules Design

이 문서는 outbound SMTP relay를 옵션 모듈로 분리하기 위한 **설계 + 구현 반영 상태**를 정리합니다.

목표:
- 기본 `kotlin-smtp-spring-boot-starter`는 inbound(수신/저장/로컬 처리) 중심으로 유지
- outbound relay(MX 조회, DNS, SMTP outbound, DSN 생성)는 `kotlin-smtp-relay*` 모듈로 분리
- 최우선 원칙: **의도치 않은 오픈 릴레이(open relay) 방지**

**최종 업데이트:** 2026-02-08  
**구현 상태:** ✅ 완료

---

## 결론(확정)

- relay public 경계는 `kotlin-smtp-relay`에 최소 API로 고정한다(자카르타 메일/dnsjava 타입 노출 금지).
- 기본 구현(dnsjava + jakarta-mail/angus 기반)은 `kotlin-smtp-relay-jakarta-mail`로 분리한다.
- Spring Boot 활성화/가드레일/프로퍼티 바인딩은 `kotlin-smtp-relay-spring-boot-starter`가 담당한다.
- `smtp.relay.enabled=true`인데 relay starter가 없으면 **부팅 단계에서 실패**하도록 한다(fail-fast).
- 외부 도메인 RCPT는 RCPT 단계에서 정책을 조기 검증한다(530/550).
- 로컬 도메인 판정은 relay 기능이 아니라 라우팅 기본값이므로 `smtp.routing.localDomain`을 권장 키로 확정한다.
  - 레거시 호환: `smtp.relay.localDomain`은 fallback으로만 유지(향후 제거 대상)

---

## 모듈 구성(확정)

### `kotlin-smtp-relay`
- **public API:** `MailRelay`, `RelayAccessPolicy`, `RelayException`, DSN 경계(`DsnSender`, `DsnStore`)
- **의존:** `kotlin-smtp-core`만
- **위치:** `kotlin-smtp-relay/src/main/kotlin/io/github/kotlinsmtp/relay/api/`

### `kotlin-smtp-relay-jakarta-mail`
- **기본 구현:** `JakartaMailMxMailRelay`, `JakartaMailDsnSender`
- **의존:** dnsjava + jakarta mail/angus + activation
- **기능:**
  - MX 레코드 조회 (dnsjava)
  - SMTP outbound 전송 (jakarta-mail)
  - STARTTLS/TLS 지원
  - DSN(RFC 3464) 생성 및 발송

### `kotlin-smtp-relay-spring-boot-starter`
- **역할:** `smtp.relay.*` properties 바인딩
- **기능:**
  - `smtp.relay.enabled` 토글 + 오픈 릴레이 가드레일
  - enabled일 때 `MailRelay`/`RelayAccessPolicy`/`DsnSender` Bean 제공
  - outbound TLS 설정 (`smtp.relay.outboundTls.*`)

---

## Public API(최소 경계)

패키지: `io.github.kotlinsmtp.relay.api`

### Core Interfaces

- **`MailRelay`** - 외부 도메인으로 메시지 릴레이
  - `suspend fun relay(request: RelayRequest): RelayResult`

- **`RelayRequest`** - 릴레이 요청 데이터
  - `messageId`, `envelopeSender`, `recipient`, `authenticated`, `rfc822`

- **`RelayResult`** - 릴레이 성공 결과
  - `remoteHost`, `remotePort`, `serverGreeting`

### Access Policy

- **`RelayAccessPolicy`** - 릴레이 접근 정책 (오픈 릴레이 방지)
  - `fun evaluate(context: RelayAccessContext): RelayAccessDecision`

- **`RelayAccessContext`** - 정책 평가 컨텍스트
  - `envelopeSender`, `recipient`, `authenticated`

- **`RelayAccessDecision`** - 정책 결정 (sealed interface)
  - `Allowed`, `Denied(reason, message)`

- **`RelayDeniedReason`** - 거부 사유 enum
  - `AUTH_REQUIRED`, `SENDER_DOMAIN_NOT_ALLOWED`, `OTHER_POLICY`

### Exceptions

- **`RelayException`** (sealed) - 릴레이 실패 기반 예외
  - `isTransient: Boolean` - 재시도 가능 여부
  - `enhancedStatusCode: String?` - RFC 3463 enhanced status code
  - `remoteReply: String?` - 원격 서버 응답

- **`RelayTransientException`** - 일시적 실패 (재시도 가능)

- **`RelayPermanentException`** - 영구적 실패 (재시도 불가)

### DSN (Delivery Status Notification)

- **`DsnSender`** - DSN 발송 인터페이스
  - `fun sendPermanentFailure(...)` - 영구 실패 DSN 발송

- **`DsnStore`** - DSN 메시지 큐 등록
  - `fun enqueue(...)` - DSN 메시지를 스풀에 등록

### Utilities

- **`RelayDefaults`** - 기본 정책 팩토리
  - `requireAuthPolicy(): RelayAccessPolicy` - 인증 필수 정책

원칙:
- outbound 구현 상세(자카르타 메일 세션, dnsjava 레코드 타입 등)를 public API에 노출하지 않는다.

---

## 구현체 상세 (`kotlin-smtp-relay-jakarta-mail`)

### `JakartaMailMxMailRelay`

MX 조회 및 SMTP 전송을 담당하는 기본 구현체입니다.

**특징:**
- dnsjava를 사용한 MX 레코드 조회 (캐싱 지원)
- MX 레코드 우선순위에 따른 순차 시도
- A/AAAA 레코드 fallback (MX 없을 시)
- jakarta-mail을 사용한 SMTP 전송
- STARTTLS 지원 (opportunistic/required)
- TLS 서버 인증서 검증 (활성화 가능)
- SMTPUTF8 지원

**구성:**
```kotlin
public class JakartaMailMxMailRelay(
    private val dispatcherIO: CoroutineDispatcher = Dispatchers.IO,
    private val tls: OutboundTlsConfig,
) : MailRelay
```

### `OutboundTlsConfig`

아웃바운드 TLS 설정 데이터 클래스:
- `ports: List<Int>` - 시도할 포트 목록 (기본: [25])
- `startTlsEnabled: Boolean` - STARTTLS 활성화 (기본: true)
- `startTlsRequired: Boolean` - STARTTLS 필수 (기본: false)
- `checkServerIdentity: Boolean` - 서버 ID 검증 (기본: true)
- `trustAll: Boolean` - 모든 인증서 신뢰 (개발용, 기본: false)
- `trustHosts: List<String>` - 신뢰 호스트 목록
- `connectTimeoutMs: Int` - 연결 타임아웃 (기본: 15000ms)
- `readTimeoutMs: Int` - 읽기 타임아웃 (기본: 15000ms)

### `JakartaMailDsnSender`

RFC 3464 compliant DSN 생성 및 발송 구현체입니다.

**필드 매핑:**
- `Action: failed` - 영구 실패
- `Status: 5.x.x` - RFC 3463 status code
- `Diagnostic-Code:` - 상세 오류 정보
- `Original-Recipient:` - 원본 수신자 (ORCPT)
- `Final-Recipient:` - 최종 수신자

---

## Starter에서 빠진 구현(확정)

`kotlin-smtp-spring-boot-starter`에서 제거(이동)된 항목:

- MX 조회 / DNS 조회 / 캐싱(dnsjava)
- SMTP outbound 전송(jakarta mail/angus)
- DSN 생성(jakarta mail/activation)
- outbound TLS/STARTTLS 설정 모델/바인딩(`smtp.relay.outboundTls.*`)
- relay 전용 auto-configuration(조건부 Bean 등록, relay 토글/가드레일)

---

## 활성화 방식 및 기본 정책

### 설정 키(권장)

**라우팅(로컬 판정):**
- `smtp.routing.localDomain` (권장)
- `smtp.relay.localDomain` (레거시 fallback)

**relay 토글/정책:**
- `smtp.relay.enabled` (기본값: `false`)
- `smtp.relay.requireAuthForRelay` (기본값: `true`)
- `smtp.relay.allowedSenderDomains` (기본값: `[]`)
- `smtp.relay.outboundTls.*` (기본값: 안전한 verify 정책)

### relay starter 미탑재 시

- `smtp.relay.enabled=false`(기본): inbound-only로 동작
  - 외부 도메인 RCPT는 `550 5.7.1 Relay access denied`로 거부
- `smtp.relay.enabled=true`: **부팅 실패(fail-fast)**
  - 이유: "설정만 켰는데 릴레이가 실제로 동작하지 않는" 운영 장애 방지

### relay 활성화(`smtp.relay.enabled=true`) 기본 정책

- `requireAuthForRelay=true`이고 미인증 세션이면(외부 도메인 RCPT 단계):
  - `530 5.7.0 Authentication required`
- allowlist가 비어있지 않고, 발신자 도메인이 allowlist에 없고 미인증이면:
  - `550 5.7.1 Relay access denied`

### 오픈 릴레이 방지 가드레일(부팅 시점)

- `smtp.relay.enabled=true`인데 아래 조건이면 **부팅 실패**
  - `smtp.relay.requireAuthForRelay=false`
  - `smtp.relay.allowedSenderDomains`가 비어있음

에러 메시지 예:
```
Refusing to start: smtp.relay.enabled=true without smtp.relay.requireAuthForRelay=true or smtp.relay.allowedSenderDomains allowlist
```

---

## 사용 예시

### 1. Core-only (Spring 없이)

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.kotlinsmtp:kotlin-smtp-core:VERSION")
    implementation("io.github.kotlinsmtp:kotlin-smtp-relay:VERSION")
    implementation("io.github.kotlinsmtp:kotlin-smtp-relay-jakarta-mail:VERSION")
}

// 사용
val relay = JakartaMailMxMailRelay(
    tls = OutboundTlsConfig(
        ports = listOf(25),
        startTlsEnabled = true,
        trustAll = false, // 운영에서는 false
    )
)
```

### 2. Spring Boot Starter 사용

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.kotlinsmtp:kotlin-smtp-spring-boot-starter:VERSION")
    implementation("io.github.kotlinsmtp:kotlin-smtp-relay-spring-boot-starter:VERSION")
}

// application.yml
smtp:
  relay:
    enabled: true
    requireAuthForRelay: true
    outboundTls:
      trustAll: false
```

---

## 테스트

Relay 모듈 테스트:
- `JakartaMailDsnSenderTest` - DSN 생성 및 필드 검증
- `RelayStarterWiringTest` - Spring Boot auto-configuration 검증

---

*이 문서는 relay 모듈 설계 및 구현의 참조 문서입니다.*
