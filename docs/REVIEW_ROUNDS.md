# Kotlin SMTP 단계별 검토 기록 (1~10차)

작성일: 2026-02-20

이 문서는 "Apache James급 완성형 SMTP 서버를 더 쉽게 조립/운영하게 해주는 라이브러리" 관점에서 진행한 라운드별 검토 결과를 정리한다.

## 진행 현황

- 1차 우선순위 1번(README/ARCHITECTURE/CONFIGURATION 문서-코드 정합성) 1차 반영 완료
- 6차 우선순위 1번(CI `apiCheck` 필수 게이트 추가) 반영 완료 (`.github/workflows/ci.yml`)
- API baseline 드리프트 해소 완료 (`kotlin-smtp-core/api/kotlin-smtp-core.api`, `kotlin-smtp-relay/api/kotlin-smtp-relay.api` 갱신 후 `./gradlew apiCheck` 통과)
- 2차 우선순위 1번(코어 단위 회귀 테스트 축 신설) 1차 반영 완료 (`kotlin-smtp-core/src/test/kotlin/**`, `:kotlin-smtp-core:test` 통과)
- 2차 우선순위 2번(AUTH LOGIN/DSN/SIZE 경계 시나리오) 1차 반영 완료 (`src/test/kotlin/io/github/kotlinsmtp/SmtpAuthStartTlsIntegrationTest.kt`, `src/test/kotlin/io/github/kotlinsmtp/SmtpIntegrationTest.kt`)
- 2차 우선순위 3번(거절 이벤트 stage 기준 정리) 1차 반영 완료 (`SmtpSession.notifyMessageRejected` 경로로 일원화)
- 2차 우선순위 4번(AUTH IP 식별 경로 일원화) 반영 완료 (`AuthCommand`가 `session.clientIpAddress()` 경로 사용)
- 3차 우선순위 1번(due-time 기반 큐 조회) 1차 반영 완료 (`SpoolMetadataStore.listDueMessages`, Redis ZSET score 조회, `MailSpooler` 적용)
- 3차 우선순위 2번(`MailSpooler` 회귀 테스트 보강) 1차 반영 완료 (재시도 증가, 도메인 부분 처리 시 attempt 보존, 중복 트리거 비중복 전달)
- 3차 우선순위 3번(Redis raw 저장 메모리 경로 완화) 1차 반영 완료 (`readAllBytes`/전체 decode 버퍼 제거, 스트리밍 Base64 인코드·디코드 적용)
- 3차 우선순위 4번(ETRN trigger rate-limit/쿨다운) 1차 반영 완료 (`MailSpooler.triggerCooldownMillis`, 초과 시 `SpoolTriggerResult.UNAVAILABLE` 반환)
- 3차 우선순위 5번(스풀 운영 메트릭 확장) 1차 반영 완료 (큐 체류시간/재시도 지연/도메인·원인별 실패 카운터 추가)
- 코드리뷰/유지보수 리뷰 반영 완료 (메트릭 카디널리티 제한, 트리거 CAS 기반 쿨다운, 파일 스토어 due N+1 경로 제거, 실패 분류 로직 보강)
- 4차 우선순위 1번(운영 런북 문서) 1차 반영 완료 (`docs/OPERATIONS.md`, `docs/README.md` 링크 추가)
- 4차 우선순위 2~3번(배포/알람 템플릿) 1차 반영 완료 (`docs/templates/docker-compose.smtp.yml`, `docs/templates/k8s-probes.yaml`, `docs/templates/prometheus-smtp-alerts.yml`)
- 4차 우선순위 5번(종료 정책 운영 옵션) 1차 반영 완료 (`smtp.lifecycle.gracefulShutdownTimeoutMs`, `SmtpServerRunner` 연동)
- 4차 운영 보강 반영 (ETRN/spool 트리거 쿨다운 설정화 `smtp.spool.triggerCooldownMillis`, k8s deployment 템플릿 추가)
- 4차 운영 문서 심화 반영 (환경별 알람 임계값, 장애 플레이북 확장, 운영 체크리스트 추가)
- 4차 CI 보강 반영 (주간/수동 성능 검증 워크플로우 `.github/workflows/performance-check.yml` 추가)
- 4차 운영 템플릿/검증 보강 (k8s Service/PDB 템플릿 추가, 신규 설정 검증 테스트 추가)
- 5차 보안 보강 선반영 (AUTH/lock 로그의 사용자·IP 마스킹 적용)
- 5차 보안 설정 보강 (relay `failOnTrustAll`, auth `allowPlaintextPasswords` 및 검증/문서 반영)
- 5차 TLS 입력 검증 보강 (`smtp.ssl.minTlsVersion` 제한, handshake/cipherSuite 유효성 검증 + 테스트 추가)
- 8차 DX 보강 선반영 (example-app 의존 버전 0.1.3 동기화, `docs/QUICKSTART.md` 추가)
- 5차 마무리 반영 (`InMemoryAuthService` 보안 동작 테스트 추가: BCrypt-only/평문 차단/명시 허용)
- 5차 리뷰 반영 완료 (AuthRateLimiter 동기화 보강, BCrypt 판별 중복 정리, Runner 정지 로직 단순화, TLSv1.3 검증 테스트 추가)
- 9차 1차 반영 완료 (JaCoCo 리포트 활성화, CI `jacocoTestReport` 실행/아티팩트 업로드, `docs/TESTING_STRATEGY.md` 추가)
- 9차 2차 반영 완료 (핵심 모듈 라인 커버리지 최소 기준선 도입, CI에 `jacocoTestCoverageVerification` 추가)
- 9차 3차 반영 완료 (커버리지 상향 계획 문서화: `docs/COVERAGE_ROADMAP.md`)
- 6차 후속 반영 완료 (API 변경 시 `CHANGELOG.md`/`docs/MIGRATION.md` 동시 갱신을 CI에서 강제)
- 6차 마지막 정리 완료 (CI push/PR 동시 강제, changelog 구조 정합화, 릴리즈/마이그레이션/PR 체크리스트 일관화)
- 7차 1차 반영 완료 (Redis 선택형 분산 rate limit 백엔드 추가: connection/message + AUTH, `smtp.rateLimit.backend`, `smtp.auth.rateLimitBackend`)
- 7차 2차 반영 완료 (스풀 in-process 병렬도 옵션 `smtp.spool.workerConcurrency` 추가)
- 10차 1차 반영 완료 (제품 포지셔닝 명확화: 엔진 vs 완제품 경계 문구 및 블루프린트 문서 추가)
- 10차 2차 반영 완료 (완성형 서버 조립 로드맵/James 대체 경로 문서화: `docs/COMPLETE_SERVER_BLUEPRINT.md`)
- 10차 3차 반영 완료 (외부 통합 레시피 보강: anti-spam/AV 연계 예시 추가, `docs/RECIPES.md`)

## 1차 검토 - 제품/플랫폼 완성도

### 핵심 결론
- 모듈 경계(코어/스타터/릴레이)와 확장 SPI 자체는 잘 설계되어 있어 "조립형 플랫폼" 기반은 좋다.
- 현재 병목은 엔진 기능 부족보다 문서 신뢰도, 운영 가이드, 회귀 검증 구조에 있다.

### 강점
- 모듈 분리와 역할이 명확함 (`README.md`, `docs/ARCHITECTURE.md`)
- 확장 포인트와 빈 오버라이드 규칙이 일관적임 (`docs/EXTENSION.md`, `docs/EXTENSION_MATRIX.md`)

### 미흡/보완
- 문서와 코드 간 드리프트 존재
  - AUTH 광고/설명 불일치 (`README.md:26`, `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/protocol/command/EhloCommand.kt:57`)
  - 아키텍처 문서 인터페이스 시그니처 일부 불일치 (`docs/ARCHITECTURE.md:116`, `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/storage/MessageStore.kt:20`)
- 운영자 관점 문서 공백
  - 배포/장애복구/롤링업데이트/운영 체크리스트 문서 부재 (`docs/`)
- API/기능 진화 가이드 부재
  - CHANGELOG/MIGRATION/ROADMAP 파일 없음

### 1차 개선 우선순위
1. README/ARCHITECTURE/RECIPES 문서-코드 정합성 정리
2. 운영 가이드(배포/장애/모니터링) 신설
3. 버전 업그레이드 가이드(CHANGELOG/MIGRATION) 도입

---

## 2차 검토 - 코어 SMTP 프로토콜/상태머신 품질

### 핵심 결론
- 기본 상태전이와 STARTTLS/BDAT 동기화 방어는 탄탄하다.
- 다만 경계 케이스 회귀 테스트가 얕아, 장기 유지보수 리스크가 남는다.

### 강점
- STARTTLS 파이프라이닝 차단과 업그레이드 게이트 처리 명확 (`kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/server/SmtpTlsUpgradeManager.kt:26`)
- DATA/BDAT 혼용 방어, BDAT drain 후 검증 처리 적절 (`kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/protocol/command/DataCommand.kt:33`, `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/protocol/command/BdatCommand.kt:78`)
- 인바운드 프레이밍에서 DATA 모드와 BDAT 자동감지 충돌을 방지 (`kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/server/SmtpInboundDecoder.kt:119`)

### 미흡/보완
- 코어 모듈 단위 테스트 축 부재
  - `kotlin-smtp-core/src/test` 실질 공백, 루트 통합 테스트 편중
- AUTH LOGIN, DSN 파라미터(RET/ENVID/NOTIFY/ORCPT), SIZE/line/chunk 상한 경계 테스트 부족
- 거절 이벤트 발행 경로가 분산되어 stage/중복 관리 복잡도 존재
  - `SmtpCommands`와 `SmtpStreamingHandlerRunner` 양쪽에서 거절 이벤트 처리
- AUTH rate-limit의 IP 추출이 문자열 파싱 기반이라 포맷 편차에 취약 가능
  - `AuthCommand.extractClientIp()` vs `SmtpSession.clientIpAddress()` 경로 불일치

### 2차 개선 우선순위
1. 코어 프로토콜 단위 회귀 테스트 세트 신설 (`kotlin-smtp-core/src/test`)
2. AUTH LOGIN/DSN 파라미터/경계값 시나리오 보강
3. 이벤트 거절 stage 기준 정리(단일 책임화)
4. AUTH IP 식별 경로 일원화

---

## 3차 검토 - 릴레이/스풀/DSN 운영 신뢰성

### 핵심 결론
- 릴레이 정책/분류/DSN 경계는 구조가 좋다.
- 운영 규모가 커질 때 스풀 처리 효율과 테스트 깊이가 병목이 될 가능성이 높다.

### 강점
- 릴레이 SPI 경계가 명확해 교체/확장 용이 (`kotlin-smtp-relay/src/main/kotlin/io/github/kotlinsmtp/relay/api/MailRelay.kt`)
- 실패 분류와 DSN 정책 분리 (`kotlin-smtp-relay-jakarta-mail/src/main/kotlin/io/github/kotlinsmtp/relay/jakarta/RelayFailureClassifier.kt`, `kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/spool/SpoolFailurePolicy.kt`)
- MTA-STS/DANE basic + TLS 정책 병합 흐름 명확 (`kotlin-smtp-relay-jakarta-mail/src/main/kotlin/io/github/kotlinsmtp/relay/jakarta/StandardOutboundRelayPolicyResolver.kt`)

### 미흡/보완
- 스풀 주기마다 전체 메시지 스캔 구조라 대형 큐에서 비효율 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/spool/MailSpooler.kt:238`)
- Redis 스풀 raw 저장 시 `readAllBytes + Base64`로 메모리 피크 부담 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/spool/RedisSpoolMetadataStore.kt:97`)
- `MailSpooler` 중심 E2E/동시성 회귀 테스트 부족 (현재 정책 단위 테스트 위주)
- ETRN/수동 trigger 남용 방어 TODO 존재 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/spool/MailSpooler.kt:93`)
- 스풀 운영 메트릭이 카운터 중심이라 원인 분석 신호 부족 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/metrics/MicrometerSpoolMetrics.kt`)

### 3차 개선 우선순위
1. due-time 기반 큐 조회 API 도입(특히 Redis ZSET score 활용)
2. `MailSpooler` 회귀 테스트 보강(락 heartbeat/재시도/부분 도메인 처리/중복방지)
3. Redis raw 저장 메모리 사용량 완화(스트리밍/청크/압축)
4. ETRN trigger rate limit/쿨다운 추가
5. 스풀 운영 메트릭 확장(대기시간, 실패 reason, 도메인 분해)

---

## 4차 검토 - 운영자 경험(배포/장애대응/관측 플레이북)

### 핵심 결론
- 라이브러리/엔진 구현은 충분히 성숙해 가고 있지만, 운영자 경험은 아직 "레퍼런스 실행" 단계다.
- 즉시 보완 효과가 큰 영역은 런북/배포 템플릿/운영 SLO 문서화다.

### 확인된 현황
- 문서 세트는 아키텍처/설정/확장 중심이며 운영 런북 문서는 없음 (`docs/README.md`)
- 컨테이너/오케스트레이션 레퍼런스 부재 (Dockerfile, compose, Helm 없음)
- CI는 `test` 중심이며 성능/호환성 게이트가 없음 (`.github/workflows/ci.yml`)
- Actuator/Prometheus 노출 예시는 있으나 운영 시나리오별 알람 기준/대응 절차는 없음 (`README.md`, `docs/application.example.yml`)
- 스프링 종료 시 SMTP stop 호출은 있으나 graceful timeout 정책을 운영자가 명시적으로 제어하는 문서/옵션 가이드는 약함
  - `SmtpServerRunner`는 `server.stop()` 기본값 사용 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/server/SmtpServerRunner.kt:42`)
  - 코어 default graceful timeout = 30s (`kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/server/SmtpServer.kt:237`)

### 미흡/보완
- 운영 시작 가이드(싱글 노드/다중 노드/프록시/포트 분리) 부재
- 장애 대응 플레이북(큐 적체, DNS 장애, TLS 실패율 급증, DSN 급증) 부재
- 관측 항목은 있으나 "정상 범위/경보 임계값/대응 액션" 부재
- 배포 표준 레퍼런스(컨테이너 이미지, health/readiness 샘플, 롤링 업데이트 절차) 부재

### 4차 개선 우선순위
1. 운영 런북 문서 신설 (`docs/OPERATIONS.md` 권장)
2. 배포 레퍼런스 제공 (Dockerfile + docker-compose 예시, 필요 시 k8s manifest 샘플)
3. SLO/알람 가이드 문서화 (메트릭별 임계값, 대응 절차)
4. CI 품질게이트 확장 (`apiCheck`, 선택적 성능 프로파일, 문서 정합성 검사)
5. graceful shutdown 운영 옵션 가이드/프로퍼티화 검토

---

## 전체 개선 로드맵 (순차 적용)

1. 문서 정합성 복구 (1차)
2. 코어 회귀 테스트 축 강화 (2차)
3. 스풀 처리 효율 + 신뢰성 강화 (3차)
4. 운영 런북/배포 템플릿/알람 체계 확립 (4차)

위 순서대로 진행하면, "기능 제공 라이브러리"에서 "운영 가능한 SMTP 서버 플랫폼"으로의 체감 완성도를 가장 빠르게 끌어올릴 수 있다.

---

## 5차 검토 - 보안 심화(TLS/AUTH/Relay 경계)

### 핵심 결론
- 기본 보안 가드레일(STARTTLS 강제 AUTH, PROXY 신뢰 CIDR, open-relay 경고)은 잘 들어가 있다.
- 다만 운영 보안 관점에서 "허용은 되어도 위험한 기본/옵션"이 남아 있고, 일부는 fail-fast보다 warn 중심이라 실수 여지가 있다.

### 강점
- AUTH를 TLS 세션에서만 허용해 평문 자격증명 노출을 차단 (`kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/protocol/command/AuthCommand.kt:43`)
- PROXY 헤더는 trusted CIDR 검증 후에만 수용 (`kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/protocol/handler/SmtpChannelHandler.kt:161`, `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/server/ProxyProtocolSupport.kt:31`)
- 릴레이 오픈 설정 시 경고 로그 제공 (`kotlin-smtp-relay-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/relay/config/KotlinSmtpRelayAutoConfiguration.kt:176`)
- outbound `trustAll=true` 사용 시 경고를 남김 (`kotlin-smtp-relay-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/relay/config/KotlinSmtpRelayAutoConfiguration.kt:184`)

### 미흡/보완
- in-memory 인증이 평문 비밀번호를 허용하는 fallback을 유지 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/auth/InMemoryAuthService.kt:23`)
  - Spring Security 권장(DelegatingPasswordEncoder/BCrypt 중심) 대비 운영 안전성 낮음
- 인증 실패/락 로그에 username, clientIp를 그대로 기록 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/auth/InMemoryAuthService.kt:25`, `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/auth/AuthRateLimiter.kt:77`)
  - 대규모 운영에서 계정 탐색 신호로 활용될 수 있음
- `trustAll=true`가 warn만 있고 차단 정책은 없음 (인터넷 노출 환경에서 실수 가능)
- inbound TLS 최소버전/암호군 validate가 제한적 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/config/SslConfig.kt:49`)
  - 값 존재성 위주이며 강도 정책(예: TLS 1.0/1.1 금지) 강제가 약함

### 5차 개선 우선순위
1. `InMemoryAuthService` 기본 정책을 해시 전용으로 전환(평문 허용은 명시 옵션으로 격리)
2. 인증 관련 로그 마스킹/샘플링 강화(username 해시화 또는 부분 익명화)
3. `trustAll=true` 운영 가드레일 강화(프로파일 제한 또는 fail-fast 옵션)
4. TLS 보안 baseline 검증 로직 강화(min version, weak cipher 차단)

---

## 6차 검토 - API/SPI 안정성 및 릴리즈 게이트

### 핵심 결론
- API 안정성을 위한 도구(BCV, explicitApi)는 채택되어 방향은 매우 좋다.
- 하지만 현재 CI 게이트에 API 검증이 빠져 있어, 공개 API 스냅샷 드리프트가 누적될 수 있다.

### 확인 결과
- 루트 빌드에 binary-compatibility-validator 적용됨 (`build.gradle.kts:4`)
- core/relay는 API 스냅샷 파일 관리 중 (`kotlin-smtp-core/api/kotlin-smtp-core.api`, `kotlin-smtp-relay/api/kotlin-smtp-relay.api`)
- CI는 `./gradlew test`만 실행, `apiCheck` 미포함 (`.github/workflows/ci.yml:40`)
- 실제 검증 실행 시 API 불일치 발생 확인
  - 실행: `./gradlew apiCheck`
  - 결과: `:kotlin-smtp-relay:apiCheck FAILED`
  - 원인: `kotlin-smtp-relay.api`와 실제 공개 API 차이(예: `RelayAccessContext.peerAddress`, `OutboundRelayPolicy*`, `RelayRoute*`, `RelayException` 시그니처 확장)

### 미흡/보완
- 공개 API 변경이 발생했는데 baseline 반영/검토 프로세스가 CI에서 강제되지 않음
- 결과적으로 "의도된 API 변경"과 "실수로 노출된 변경" 구분이 PR 단계에서 어려움

### 6차 개선 우선순위
1. CI에 `apiCheck`를 필수 게이트로 추가 (`.github/workflows/ci.yml`)
2. 릴리즈 전용 체크리스트에 `apiCheck` + 변경 근거(why) 문서화 추가
3. API 변경 시 `apiDump`만으로 끝내지 않고, 릴리즈 노트/마이그레이션 항목 동시 갱신 규칙 도입

---

## 전체 개선 로드맵 (업데이트)

1. 문서 정합성 복구 (1차)
2. 코어 회귀 테스트 축 강화 (2차)
3. 스풀 처리 효율 + 신뢰성 강화 (3차)
4. 운영 런북/배포 템플릿/알람 체계 확립 (4차)
5. 보안 기본정책 강화(TLS/AUTH/로그/relay guardrail) (5차)
6. API 안정성 게이트를 CI에 고정 (6차)

---

## 7차 검토 - 확장성/성능 설계(고부하 운영 관점)

### 핵심 결론
- 현재 구조는 중소 규모 트래픽에서는 충분히 실용적이다.
- 하지만 고부하/다중 노드 운영으로 가면 큐 조회/레이트리밋/메모리 경로에서 선형 비용이 커질 수 있다.

### 강점
- 성능 측정 문서와 재현 절차(JMH/performanceTest)가 잘 정리됨 (`docs/PERFORMANCE.md`)
- 세션 입력 채널 용량 제한(1024)과 backpressure 경로가 있어 메모리 폭주 방어 기반이 있음 (`kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/server/SmtpSession.kt:36`)
- 스풀 백오프에 jitter를 넣어 thundering herd 완화 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/spool/MailSpooler.kt:229`)

### 미흡/보완
- 스풀 처리 시 전체 메시지 목록을 매번 조회/순회
  - 파일/Redis 모두 `listMessages()` 기반 전수 스캔 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/spool/MailSpooler.kt:239`)
  - Redis도 `ZSET range 0..-1` 전체 조회 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/spool/RedisSpoolMetadataStore.kt:58`)
- 레이트리밋이 메모리 로컬 상태라 다중 인스턴스에서 전역 일관성 없음 (`kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/server/RateLimiter.kt:14`, `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/auth/AuthRateLimiter.kt:17`)
- Redis 스풀 raw 저장 시 `readAllBytes + Base64` 경로로 큰 메시지에서 메모리 피크 부담 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/spool/RedisSpoolMetadataStore.kt:97`)
- 스풀 워커 동시성 제어가 단일 루프 + mutex 중심이라 대형 큐 처리량 확장 포인트가 제한적 (`kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/spool/MailSpooler.kt:211`)

### 7차 개선 우선순위
1. due-message 우선 조회 API로 전환 (Redis `ZRANGEBYSCORE`/파일 인덱스 전략)
2. 분산 레이트리밋(선택형 Redis backend) 도입 검토
3. Redis raw 저장의 스트리밍/압축 옵션 도입으로 메모리 피크 완화
4. 스풀 병렬도 제어(도메인별/파티션별 worker) 설계 옵션 추가

---

## 전체 개선 로드맵 (최신)

1. 문서 정합성 복구 (1차)
2. 코어 회귀 테스트 축 강화 (2차)
3. 스풀 처리 효율 + 신뢰성 강화 (3차)
4. 운영 런북/배포 템플릿/알람 체계 확립 (4차)
5. 보안 기본정책 강화(TLS/AUTH/로그/relay guardrail) (5차)
6. API 안정성 게이트를 CI에 고정 (6차)
7. 고부하 확장성(큐 조회/분산 제어/메모리 경로) 개선 (7차)

---

## 8차 검토 - DX/온보딩/설정 경험

### 핵심 결론
- 문서량은 충분하고 구조도 좋지만, 신규 사용자 관점에서는 "첫 성공까지의 경로"가 아직 길다.
- 특히 예제/문서 버전 정합성과 안전 기본값 안내를 더 강하게 묶어야 실사용 진입장벽이 낮아진다.

### 강점
- 문서 인덱스와 학습 경로가 이미 체계화됨 (`docs/README.md`)
- 설정 레퍼런스가 상세하고 실제 예제 YAML도 제공 (`docs/CONFIGURATION.md`, `docs/application.example.yml`)
- Spring Boot 설정 메타데이터 processor(kapt) 적용으로 IDE 보조 기반 확보 (`kotlin-smtp-spring-boot-starter/build.gradle.kts:40`, `kotlin-smtp-relay-spring-boot-starter/build.gradle.kts:38`)

### 미흡/보완
- 예제 앱이 현재 저장소 버전(`0.1.3`)이 아닌 고정 `0.1.2` 의존 (`kotlin-smtp-example-app/build.gradle.kts:23`)
  - 로컬 코드와 예제 동작이 어긋날 수 있음
- README feature 목록에 `AUTH PLAIN`만 기재되어 LOGIN 지원 사실이 희미함 (`README.md:26`)
- 예제 앱 기본 설정이 `relay.enabled=true` + `requireAuthForRelay=false` 조합이라 온보딩 시 오해 소지 (`kotlin-smtp-example-app/src/main/resources/application.yml:23`)
  - 실전에서는 안전 모드(인증 요구)를 먼저 체험시키는 편이 바람직
- 문서가 풍부한 대신 "10분 Quickstart"(의존성 + 안전 설정 + 검증 명령 3단계) 형태의 초단기 경로가 없음

### 8차 개선 우선순위
1. 예제 앱 의존 버전/설정을 현재 릴리즈 기준으로 동기화
2. README feature/지원 명세를 코드 기준으로 정합화
3. 보안 안전 기본값 중심 Quickstart 섹션 신설
4. 온보딩 검증 커맨드(예: EHLO/MAIL/RCPT/DATA smoke)를 문서에 고정 템플릿으로 제공

---

## 9차 검토 - 테스트 전략/품질 게이트 완성도

### 핵심 결론
- 모듈별 단위 테스트는 일부 잘 갖춰져 있으나, 계층별 테스트 피라미드와 품질 게이트가 아직 불균형이다.
- 특히 코어 프로토콜 모듈의 단위 회귀 축과 커버리지 가시성이 약하다.

### 강점
- relay-jakarta-mail, starter 계열은 정책/유틸/오토설정 테스트가 비교적 충실 (`kotlin-smtp-relay-jakarta-mail/src/test/kotlin`, `kotlin-smtp-spring-boot-starter/src/test/kotlin`)
- 루트 통합 테스트에서 STARTTLS/AUTH/lifecycle 핵심 시나리오 검증 (`src/test/kotlin/io/github/kotlinsmtp/SmtpIntegrationTest.kt`, `src/test/kotlin/io/github/kotlinsmtp/SmtpAuthStartTlsIntegrationTest.kt`)

### 미흡/보완
- 코어/relay API 모듈의 `src/test` 공백
  - `kotlin-smtp-core/src/test`, `kotlin-smtp-relay/src/test` 없음
- 커버리지 게이트(jacoco/kover) 부재로 변경 영향도 정량 관리가 어려움 (빌드 스크립트 전반)
- CI가 현재 `./gradlew test`만 수행해, API/성능/문서 정합성 체크가 파이프라인에 고정되지 않음 (`.github/workflows/ci.yml:40`)
- 6차에서 확인된 API 드리프트(`apiCheck` 실패)가 CI에서 조기 차단되지 않음

### 9차 개선 우선순위
1. 코어 프로토콜 단위 테스트 세트(파서/상태전이/경계값) 신설
2. 커버리지 리포트 + 최소 기준(모듈별) 도입
3. CI 품질 게이트 확장: `test + apiCheck` 필수, 성능 검사는 주기/수동 프로파일로 분리
4. 테스트 피라미드 기준 문서화(단위/통합/E2E 책임 분리)

---

## 전체 개선 로드맵 (확장판)

1. 문서 정합성 복구 (1차)
2. 코어 회귀 테스트 축 강화 (2차)
3. 스풀 처리 효율 + 신뢰성 강화 (3차)
4. 운영 런북/배포 템플릿/알람 체계 확립 (4차)
5. 보안 기본정책 강화(TLS/AUTH/로그/relay guardrail) (5차)
6. API 안정성 게이트를 CI에 고정 (6차)
7. 고부하 확장성(큐 조회/분산 제어/메모리 경로) 개선 (7차)
8. 온보딩/DX 단순화(예제/문서/Quickstart) (8차)
9. 테스트 피라미드 + 품질 게이트 완성 (9차)

---

## 10차 검토 - "풀 완성형 SMTP 서버" 기능 갭 분석

### 핵심 결론
- 현재 프로젝트는 "SMTP 수신/중계 엔진"으로는 충분히 실전적이지만,
  Apache James급 "완성형 메일 서버 플랫폼"을 바로 대체하기에는 기능 범위가 의도적으로 더 좁다.
- 즉, 포지션은 "완제품"보다는 "완제품을 빠르게 조립하는 프레임워크"에 가깝고, 이 방향 자체는 타당하다.

### 확인된 갭(현재 범위 밖 또는 미제공)
- 메일 인증/정책 생태계 전반
  - DKIM/SPF/DMARC/ARC 처리 계층
- 수신 후 고도화 파이프라인
  - 스팸/악성코드/콘텐츠 정책 엔진 연계
- 사서함 접근 프로토콜 계층
  - IMAP/POP3/JMAP 등 최종 사용자 접근 계층
- 운영 제어면
  - 관리자 API/큐 관리 UI/운영 CLI 같은 제어 채널

### 전략적 해석
- 이 기능들을 모두 코어에 넣기보다,
  현재처럼 SPI/모듈 경계를 유지한 채 "옵션 모듈"로 확장하는 전략이 적합하다.
- 핵심은 "무엇을 내장할지"보다 "어떤 조립 레일을 공식 제공할지"를 명확히 하는 것.

### 10차 개선 우선순위
1. 제품 포지셔닝 문구 명확화(README/Docs): 엔진 vs 완제품 경계 선언
2. 확장 로드맵 공개: 보안 정책 모듈, 운영 제어 모듈, 사용자 접근 프로토콜 모듈
3. 외부 통합 레시피 강화: anti-spam/AV/policy-engine 연동 예제 추가
4. "James 대체 경로" 문서화: 필수 모듈 조합 + 단계별 도입 시나리오

---

## 전체 개선 로드맵 (최종 확장)

1. 문서 정합성 복구 (1차)
2. 코어 회귀 테스트 축 강화 (2차)
3. 스풀 처리 효율 + 신뢰성 강화 (3차)
4. 운영 런북/배포 템플릿/알람 체계 확립 (4차)
5. 보안 기본정책 강화(TLS/AUTH/로그/relay guardrail) (5차)
6. API 안정성 게이트를 CI에 고정 (6차)
7. 고부하 확장성(큐 조회/분산 제어/메모리 경로) 개선 (7차)
8. 온보딩/DX 단순화(예제/문서/Quickstart) (8차)
9. 테스트 피라미드 + 품질 게이트 완성 (9차)
10. 완성형 서버 지향 확장 로드맵 명시 (10차)
