# Module Strategy (옵션 모듈 분리 초안)

이 문서는 `kotlin-smtp`를 "범용 라이브러리"로 배포하기 위해,
`kotlin-smtp-spring-boot-starter`에 뭉쳐 있을 수 있는 인프라 통합(S3/Kafka/DB 등)을
어떤 단위로 분리하는 것이 좋은지에 대한 **모듈 전략 초안**입니다.

목표는 다음 두 가지를 동시에 만족하는 것입니다.

1) starter만 추가하면 최소 설정으로 바로 기동되는 경험(MVP)
2) 특정 인프라/벤더 종속(AWS SDK, Kafka client, JDBC driver 등)을 기본 의존으로 강제하지 않기

## 원칙

- `kotlin-smtp-core`는 Spring-free이며, 인프라 SDK에 직접 의존하지 않습니다.
- `kotlin-smtp-spring-boot-starter`는 "기동 경험"(auto-config)과 "레퍼런스 구현"을 제공합니다.
- S3/Kafka/DB 같은 통합은 기본값이 아니라 **옵션 모듈**(또는 사용자가 직접 구현/등록하는 Bean)로 둡니다.
- 모듈 간 의존 방향은 단방향을 유지합니다: `integration` -> `core` (권장: `core` -> `integration` 금지)

## 가장 먼저 결정할 것(권장 우선순위)

### 1) starter에서 "outbound relay"를 분리할지 여부

현재 starter는 relay 관련 구현을 포함하고 있고, 이 경로는 상대적으로 무거운 의존(dnsjava, jakarta mail/angus)을 끌어옵니다.

권장 옵션:

- 옵션 A(현상 유지): relay는 starter에 남기되, 계속 "기본 off"로 유지
  - 장점: starter 하나로 대부분 기능을 체험 가능
  - 단점: starter 의존이 무거워짐

- 옵션 B(권장): relay를 별도 모듈로 분리
  - 예: `kotlin-smtp-relay` + `kotlin-smtp-relay-spring-boot-starter`
  - starter는 로컬 수신/파일 저장/spool까지만 제공
  - 장점: starter 기본 의존이 가벼워지고, 인프라 선택지가 더 명확해짐

### 2) 옵션 모듈의 "starter" 형태를 제공할지 여부

권장: `*-spring-boot-starter`를 별도로 제공해 auto-config를 분리합니다.

- `kotlin-smtp-foo` (Spring-free 구현)
- `kotlin-smtp-foo-spring-boot-starter` (Spring auto-config)

## 용어

- core: 엔진/프로토콜/세션 + 최소 SPI
- starter: Spring Boot auto-configuration + 기본 구현(파일 기반 store/spool 등)
- integration module: 특정 인프라 구현(예: S3 MessageStore, Kafka SmtpEventHook)
- integration starter: integration module을 Spring Boot에서 쉽게 붙일 수 있는 auto-config

## 현재 구조(2026-02)

- `kotlin-smtp-core`
  - Netty 기반 엔진, SMTP state machine, DATA/BDAT 수신, STARTTLS/AUTH
  - 확장점(SPI): `MessageStore`, `AuthService`, `SmtpProtocolHandler`, `SmtpEventHook` 등

- `kotlin-smtp-spring-boot-starter`
  - `smtp.*` 설정을 기반으로 `SmtpServer`(들)을 만들고 lifecycle에 연결
  - 기본 구현: 파일 기반 `MessageStore`, 로컬 mailbox, spool/retry, outbound relay 등

## 권장 목표 구조(점진적 분리)

### 필수(기본)

- `kotlin-smtp-core`
  - 엔진 + SPI (Spring-free)

- `kotlin-smtp-spring-boot-starter`
  - auto-config + "가벼운" 기본 구현
  - 기본 구현은 가능한 한 파일/로컬 기반으로 유지
  - 인프라 SDK(클라우드/메시징/DB)는 가능한 한 제외

### 옵션(통합 모듈)

아래는 "모듈 후보"이며, 실제로는 사용자가 필요로 하는 범위에 맞춰 추가합니다.

- 스토리지(원문 EML 저장)
  - `kotlin-smtp-storage-s3` (Spring-free)
    - `MessageStore` 구현: raw EML을 S3(Object Storage)에 저장
  - `kotlin-smtp-storage-s3-spring-boot-starter`
    - S3 설정 바인딩 + `MessageStore` Bean auto-config

- 이벤트/후처리 파이프라인
  - `kotlin-smtp-event-kafka` (Spring-free 또는 Spring 선택)
    - `SmtpEventHook` 구현: Accepted/Rejected/Session 이벤트를 Kafka로 발행
  - `kotlin-smtp-event-kafka-spring-boot-starter`
    - Kafka producer 설정 + `SmtpEventHook` Bean auto-config

- 메타데이터(운영/감사)
  - `kotlin-smtp-metadata-jdbc` (권장: Spring-free)
    - `SmtpEventHook` 기반으로 메시지/세션 메타를 JDBC로 기록
  - `kotlin-smtp-metadata-jdbc-spring-boot-starter`
    - DataSource/Tx 사용 시 Spring 연동 auto-config

## API/SPI 경계 가이드

옵션 모듈을 가능하게 만드는 핵심은 "core가 제공하는 경계"를 명확히 하는 것입니다.

- raw 저장: `io.github.kotlinsmtp.storage.MessageStore`
  - core는 raw 저장을 강제하지 않고, handler(기본 또는 사용자)가 store를 호출
  - integration module은 `MessageStore` 구현체를 제공

- 이벤트: `io.github.kotlinsmtp.spi.SmtpEventHook`
  - core는 이벤트 시점을 제공하고, 통합은 hook 구현이 담당
  - hook은 Non-fatal 정책(실패해도 서버는 계속 처리)이며, 구현은 빠르게 반환하는 것을 권장

## Spring Boot에서의 wiring 패턴(권장)

starter/옵션 starter는 "사용자가 Bean을 주면 그게 우선"이 되도록 설계합니다.

- 기본값 제공: `@ConditionalOnMissingBean`
- 옵션 통합 활성화: `@ConditionalOnClass` + `@ConditionalOnProperty(prefix=..., name="enabled")`
- 여러 hook 지원: `ObjectProvider<SmtpEventHook>`로 ordered hooks를 수집

## 단계적 적용 제안

현실적인 분리 순서를 권장합니다.

1) 문서/계약 확정
   - 이 문서의 목표/의존 방향/모듈 명명 규칙 확정
   - starter의 "기본 구현" 범위(포함/제외)를 합의

2) 가장 무거운 의존부터 옵션화
   - 예: outbound relay(dnsjava/jakarta mail), Kafka client, AWS SDK, JDBC driver
   - starter는 해당 기능을 "기본 off"로 두거나 별도 모듈로 이동

3) 통합 모듈마다 최소 smoke test 추가
   - 옵션 starter를 붙였을 때 context가 깨지지 않는지
   - 훅/스토어가 실제로 등록되는지

## 비고

- 이 문서는 설계 초안이며, 실제 구현은 public API 경계가 더 안정화된 뒤에 진행합니다.
- 모듈 분리는 "기능 분해"보다 "의존성/벤더 종속 분리"를 최우선으로 합니다.
