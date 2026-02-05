# Relay Modules Design

이 문서는 outbound SMTP relay를 옵션 모듈로 분리하기 위한 **설계 + 구현 반영 상태**를 정리합니다.

목표:
- 기본 `kotlin-smtp-spring-boot-starter`는 inbound(수신/저장/로컬 처리) 중심으로 유지
- outbound relay(MX 조회, DNS, SMTP outbound, DSN 생성)는 `kotlin-smtp-relay*` 모듈로 분리
- 최우선 원칙: **의도치 않은 오픈 릴레이(open relay) 방지**

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

- `kotlin-smtp-relay`
  - public API: `MailRelay`, `RelayAccessPolicy`, `RelayException`, DSN 경계(`DsnSender`, `DsnStore`)
  - 의존: `kotlin-smtp-core`만

- `kotlin-smtp-relay-jakarta-mail`
  - 기본 구현: `JakartaMailMxMailRelay`, `JakartaMailDsnSender`
  - 의존: dnsjava + jakarta mail/angus + activation

- `kotlin-smtp-relay-spring-boot-starter`
  - `smtp.relay.*` properties 바인딩
  - `smtp.relay.enabled` 토글 + 오픈 릴레이 가드레일
  - enabled일 때 `MailRelay`/`RelayAccessPolicy`/`DsnSender` Bean 제공

---

## Public API(최소 경계)

패키지: `io.github.kotlinsmtp.relay.api`

- `MailRelay` / `RelayRequest` / `Rfc822Source` / `RelayResult`
- `RelayAccessPolicy` / `RelayAccessContext` / `RelayAccessDecision` / `RelayDeniedReason`
- `RelayException` / `RelayTransientException` / `RelayPermanentException`
- `DsnSender` / `DsnStore`

원칙:
- outbound 구현 상세(자카르타 메일 세션, dnsjava 레코드 타입 등)를 public API에 노출하지 않는다.

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

- 라우팅(로컬 판정)
  - `smtp.routing.localDomain` (권장)
  - `smtp.relay.localDomain` (레거시 fallback)

- relay 토글/정책
  - `smtp.relay.enabled` (기본값: `false`)
  - `smtp.relay.requireAuthForRelay` (기본값: `true`)
  - `smtp.relay.allowedSenderDomains` (기본값: `[]`)
  - `smtp.relay.outboundTls.*` (기본값: 안전한 verify 정책)

### relay starter 미탑재 시

- `smtp.relay.enabled=false`(기본): inbound-only로 동작
  - 외부 도메인 RCPT는 `550 5.7.1 Relay access denied`로 거부
- `smtp.relay.enabled=true`: **부팅 실패(fail-fast)**
  - 이유: “설정만 켰는데 릴레이가 실제로 동작하지 않는” 운영 장애 방지

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
- `Refusing to start: smtp.relay.enabled=true without smtp.relay.requireAuthForRelay=true or smtp.relay.allowedSenderDomains allowlist`
