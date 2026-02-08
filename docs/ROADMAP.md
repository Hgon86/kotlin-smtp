# Kotlin SMTP 프로젝트 로드맵

> 작업 추적 및 다음 세션을 위한 가이드

## 현재 상태 (Current State)

- **모듈 구조**: `kotlin-smtp-core`(Spring-free) + `kotlin-smtp-spring-boot-starter`(wiring)
- **테스트**: `./gradlew test` 통과
- **진행 상황 상세**: `docs/STATUS.md` 참고

### 최근 완료(2026-02-06)

- [x] `MailSpooler` 트리거 coalescing 구현 + `TriggerCoalescerTest`로 병합 규칙 고정
- [x] DSN RFC 3464 세부 필드 매핑 보강 + `JakartaMailDsnSenderTest` 추가
- [x] SMTPUTF8/IDN 경계 테스트 추가 및 local-part 기준 검증 보정

---

## 완료된 작업 (Completed)

### Phase 0: 기반 구조 확립 ✅

- [x] 패키지명 변경: `com.crinity.kotlinsmtp` → `io.github.kotlinsmtp`
- [x] Core 모듈 분리: Spring-free 엔진/프로토콜을 `kotlin-smtp-core`로 이동
- [x] AuthRegistry 전역 제거: `SmtpServer.authService` 주입 방식으로 변경
- [x] (core) jakarta.mail 의존성 제거: 주소 검증 로직 단순화

### Phase 4: Runtime 안정화 + 유지보수 리팩터링 ✅

- [x] STARTTLS 업그레이드 안정화(파이프라이닝 방지, 게이트, 핸드셰이크 동기화)
- [x] inbound 이중 버퍼 제거 및 메모리 상한 강화(BDAT inflight cap 우회 방지)
- [x] 백프레셔 도입(autoRead 토글)
- [x] 큰 파일 책임 분리(STARTTLS/backpressure/BDAT/reset)
- [x] DATA/BDAT 공통 처리 유틸 추출
- [x] 세션 시작 게이팅을 state machine 형태로 명확화

### 기능 보강: ETRN 도메인 처리 ✅

- [x] `ETRN <domain>` 인자 필수 검증(빈 인자/유효하지 않은 도메인 거부)
- [x] IDNA ASCII 도메인 정규화 적용
- [x] `SmtpDomainSpooler` 확장 훅 추가(도메인 지원 스풀러는 도메인 단위 트리거)
- [x] starter `MailSpooler` 도메인 지정 처리 지원
- [x] 통합 테스트(ETRN 빈 인자/정상 인자/도메인 전달) 보강

### a4: Windows 경로 기본값 제거 + 호스트 설정 정리 ✅

- [x] Windows 특정 경로(`C:\smtp-server\...`) 기본값 제거
- [x] 설정값 필수화 및 유효성 검증 추가
- [x] `application.yml` 예시를 OS 중립(relative path) + env override 중심으로 정리
- [x] README 업데이트 (라이브러리화 목표/모듈 구조/설정 원칙)

---

## 남은 작업 (Remaining Work)

남은 작업은 `docs/STATUS.md`에 상세히 정리합니다. 이 파일은 "한눈에 보는 목록"만 유지합니다.

### 1. Public API 경계 확정 (core)

**목표**: host가 의존해야 하는 타입만 public으로 안정화(semver 대상)하고, 내부(Netty/프레이밍/세션 구현)는 숨김

**TODO**:
- [x] `AUTH PLAIN` 성공/실패 시나리오 테스트
- [x] `STARTTLS` 핸드셰이크 성공/실패 테스트
- [x] TLS upgrade 후 세션 상태 유지 테스트
- [x] 인증 없이 MAIL FROM 거부 테스트 (requireAuthForMail)
- [x] STARTTLS 없이 AUTH 시도 거부 테스트

**관련 파일**:
- `src/test/kotlin/...` (테스트 디렉토리 확인 필요)
- `core/src/main/kotlin/.../auth/`
- `core/src/main/kotlin/.../server/SmtpSession.kt`

**우선순위**: HIGH (보안 관련 기능)

---

### 2. Spring Boot Starter 기능/문서 마감 ✅

**목표**: core + starter 의존 + 최소 설정으로 서버가 바로 기동되도록 auto-config/문서/스모크 테스트를 마감

**TODO**:
- [x] public package 목록 확정 (기준: host가 구현/호출해야 하는 인터페이스)
- [x] internal/impl 패키지로 이동 대상 분류 (Netty 파이프라인/디코더/프레임 등)
- [x] Kotlin `internal` + 패키지 네이밍 정책 적용
- [x] `PUBLIC_API_CANDIDATES.md`를 실제 코드와 1:1로 맞추기
- [x] Starter 설정값 검증 강화 (`SmtpServerProperties.validate()`)
- [x] application.example.yml과 실제 프로퍼티 정합
- [x] 스모크 테스트 보강 (설정 검증 테스트)
- [ ] (선택) Java 친화 facade 필요 여부 결정

**출력물**:
- `docs/PUBLIC_API_CANDIDATES.md`: "무엇이 public API인가"와 변경 규칙 (업데이트 완료)

**우선순위**: HIGH ✅ 완료

---

### 3. 배포 준비(Gradle publish + 라이선스 + 릴리즈 프로세스)

**목표**: 기능 변경 없이 유지보수성/가독성/테스트 용이성 개선 (public API는 그대로 유지)

**언제 하는 게 좋은가**:
- public API 경계를 먼저 확정한 뒤(1.5), 그 안에서 마음껏 정리하는 편이 비용이 적음
- AUTH/STARTTLS 같은 보안/상태머신 테스트가 있는 상태에서 진행하면 회귀를 바로 잡을 수 있음

**TODO**:
- [ ] core 내부 패키지 정리 (`internal`/`impl` 규칙 적용, Netty 파이프라인 구성은 숨김)
- [ ] 경고/냄새 제거 (shadowed extension, unused vars, named-arg mismatch 등)
- [ ] 큰 파일/책임 분리 (예: 세션/상태머신/프레이밍/전달 트리거)
- [ ] 로깅/응답 생성 정책 단일화(가능하면)

**우선순위**: MEDIUM

---

### 4. 설정 시스템 고도화

**목표**: 운영환경에서 안전하고 유연한 설정 관리

**TODO**:
- [ ] Spring Profile 기반 설정 분리 (dev/prod)
- [ ] 민감 설정(비밀번호, 인증서) 외부화 (환경변수/시크릿 매니저)
- [ ] 설정 값 검증 강화 (annotated validation)
- [ ] 설정 문서 자동 생성 (spring-boot-configuration-processor)

**관련 파일**:
- `kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/config/SmtpServerProperties.kt`
- `kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/config/KotlinSmtpAutoConfiguration.kt`
- `docs/application.example.yml`

**우선순위**: MEDIUM

---

### 5. Maven 배포 준비 (라이브러리)

**목표**: core + starter를 독립 artifact로 배포 가능하게 정리

**TODO**:
- [~] gradle publish 설정(artifactId, POM, license, scm)
- [ ] 버전 정책(semver) 문서화
- [ ] starter의 의존성 범위 점검 (core는 Spring-free 유지)

진행 메모:
- publishable 모듈(`core/relay/relay-jakarta-mail/relay-spring-boot-starter/spring-boot-starter`)에 `maven-publish` 공통 설정을 추가해 `publishToMavenLocal` 경로를 확보함.
- `LICENSE`를 추가해 라이선스 명시를 시작함. (POM metadata/scm/developers는 후속)

**우선순위**: MEDIUM

---

### 6. 메트릭 및 모니터링 (관측성)

**목표**: 운영 환경에서 상태 파악 가능한 메트릭 노출

**TODO**:
- [ ] Micrometer 메트릭 추가:
  - `smtp.connections.active` (활성 세션 수)
  - `smtp.messages.received` (수신 메시지 카운터)
  - `smtp.messages.delivered` (전달 성공/실패 카운터)
  - `smtp.spool.size` (스풀 큐 길이)
  - `smtp.auth.failures` (인증 실패 카운터)
- [ ] Health indicators 구현 (spool 디렉토리 쓰기 가능 여부 등)
- [ ] Grafana dashboard 예시

**관련 파일**:
- `core/src/main/kotlin/io/github/kotlinsmtp/server/SmtpServer.kt`
- `core/src/main/kotlin/io/github/kotlinsmtp/spool/MailSpooler.kt`

**우선순위**: MEDIUM

---

### 7. 문서 정리 및 통합

**목표**: 문서 일관성 확보 및 중복 제거

**TODO**:
- [ ] `docs/` 디렉토리 정리:
  - 통합된 ROADMAP.md는 이미 생성됨
  - 중복/구식 문서 제거 검토 필요
- [ ] `docs/THIN_ARCHITECTURE.md` 업데이트 (현재 런타임 흐름 반영)
- [ ] API 문서화 (KDoc 활용)

**관련 파일**:
- `docs/*.md`
- [x] `READMD.md` → `README.md` 정리

**우선순위**: MEDIUM

---

### 8. Spring Boot Starter 구조 재정돈(옵션 모듈 분리) ✅

**목표**: starter는 "기동 경험 + 로컬 기본 구현"만 제공하고, 특정 인프라(S3/Kafka/DB 등) 통합은 옵션 모듈로 분리

**설계 문서**:
- `docs/MODULE_STRATEGY.md`
- `docs/RELAY_MODULES.md` (relay 경계/정책 확정)

**TODO**:
- [x] starter에 남길 "기본 구현" 범위 확정(파일 store/spool/로컬 mailbox)
- [x] 옵션 모듈 후보/명명 규칙/의존 방향 확정
- [x] (결정) relay(outbound)를 옵션 모듈로 분리(`kotlin-smtp-relay`)
- [x] relay 모듈 public API/기본 정책 문서 확정 (`docs/RELAY_MODULES.md`)
- [x] 구현체 완성 (`JakartaMailMxMailRelay`, `JakartaMailDsnSender`)
- [x] Relay 모듈 문서화 (API + 구현체 상세)
- [ ] (선택) `kotlin-smtp-*-spring-boot-starter` 형태로 통합 모듈 auto-config 제공 여부 결정

**우선순위**: HIGH ✅ 완료

---

### 9. Example App (포트폴리오용, 마지막에 진행)

**목표**: 라이브러리(core + starter)를 소비해서 SMTP 서버 앱까지 구성

**TODO**:
- [x] `kotlin-smtp-example-app` 모듈 생성
- [x] core + starter 의존
- [ ] 실제 운영/배포는 목적이 아니라 "사용 예시 + 포트폴리오"로 구성

**우선순위**: MEDIUM

---

### 10. CI/CD 파이프라인

**목표**: 자동화된 빌드/테스트/배포

**TODO**:
- [ ] GitHub Actions workflow (테스트 + 빌드)
- [ ] 자동 버저닝 (semantic-release 또는 수동)
- [ ] Maven Central 배포 준비 (라이브러리 배포 시)

**새 파일**:
- `.github/workflows/ci.yml`

**우선순위**: LOW (안정화 후)

---

## 우선순위 추천

### 다음 작업 추천 (다음 세션용)

**옵션 A**: `6` - 메트릭 및 모니터링 (추천)
- Micrometer 메트릭 추가 (연결 수, 메시지 수, 스풀 크기 등)
- Health indicators 구현
- 운영 환경에서 필수적인 관측성 확보

**옵션 B**: `7` - 문서 정리 및 통합
- `docs/THIN_ARCHITECTURE.md` 최신화 (현재 런타임 흐름 반영)
- 중복/구식 문서 제거 검토
- API 문서화 (KDoc 활용)

**옵션 C**: `3` - 배포 준비
- Maven Central 배포 설정 완료
- 버전 정책 문서화
- 릴리즈 체크리스트 작성

### 장기 로드맵

1. **안정화 단계** (현재 ~ 1주) ✅ 대부분 완료
   - [x] Public API 경계 확정
   - [x] Starter 기능 마감
   - [x] Relay 모듈 완성
   - [ ] 메트릭 및 모니터링
   - [ ] 문서 정리

2. **운영화 단계** (1~3주)
   - Docker/K8s 배포
   - CI/CD (GitHub Actions)
   - Maven Central 배포

3. **확장 단계** (3주+)
   - PostgreSQL/R2DBC 지원 (auth + 메타데이터)
   - S3 저장소 지원
   - 고급 모니터링/알림

---

## 세션 간 전달 정보

### 작업 시작 시 확인사항

1. **최신 브랜치**: `dev` 브랜치에서 작업
2. **테스트 실행**: `./gradlew test` 통홥 확인
3. **워킹 트리**: `git status`로 클린 상태 확인
4. **문서**: 이 ROADMAP.md의 TODO 항목 확인

### 작업 완료 시

1. **테스트**: `./gradlew test` 통과 확인
2. **커밋 메시지**: `type: description` 형식 (예: `test: add AUTH failure scenarios`)
3. **문서 업데이트**: 이 ROADMAP.md의 해당 항목을 [x]로 표시
4. **다음 작업**: 다음 세션을 위해 TODO 업데이트

### 주의사항

- **Breaking Changes**: 라이브러리 public API 변경 시 문서화
- **테스트**: 모든 신규 기능은 테스트와 함께
- **보안**: 인증/TLS 관련 변경은 보안 검토 필수
- **설정**: 새 설정 추가 시 `docs/application.example.yml`도 업데이트

---

## 참고 문서

- [CORE_EXTRACTION_PLAN.md](CORE_EXTRACTION_PLAN.md) - Core 분리 계획 (완료)
- [THIN_ARCHITECTURE.md](THIN_ARCHITECTURE.md) - 아키텍처 개요
- [PUBLIC_API_CANDIDATES.md](PUBLIC_API_CANDIDATES.md) - 공개 API 정의
- [MODULE_STRATEGY.md](MODULE_STRATEGY.md) - 옵션 모듈 분리(모듈 전략)


---

## 연락/질문

- 프로젝트 구조 관련: `kotlin-smtp-core/` 모듈 참조
- 설정 관련: `kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/config/SmtpServerProperties.kt` 및 `docs/application.example.yml` 참조
- 아키텍처 관련: `docs/THIN_ARCHITECTURE.md` 참조

---

*최종 업데이트: 2026-02-09*
*다음 검토 예정: 작업 완료 시마다 업데이트*
