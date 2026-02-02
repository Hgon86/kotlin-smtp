# Kotlin SMTP 프로젝트 로드맵

> 작업 추적 및 다음 세션을 위한 가이드

## 현재 상태 (Current State)

- **Base Package**: `io.github.kotlinsmtp` (리팩토링 완료)
- **모듈 구조**: `kotlin-smtp-core` (라이브러리) + `kotlin-smtp-spring-boot-starter` (Spring Boot 자동설정)
- **최근 커밋**: `8f0e121 refactor: extract core module and rename base package`
- **테스트**: `./gradlew test` 통과

---

## 완료된 작업 (Completed)

### Phase 0: 기반 구조 확립 ✅

- [x] 패키지명 변경: `com.crinity.kotlinsmtp` → `io.github.kotlinsmtp`
- [x] Core 모듈 분리: Spring-free 엔진/프로토콜을 `kotlin-smtp-core`로 이동
- [x] AuthRegistry 전역 제거: `SmtpServer.authService` 주입 방식으로 변경
- [x] jakarta.mail 의존성 제거: 주소 검증 로직 단순화

### a4: Windows 경로 기본값 제거 + 호스트 설정 정리 ✅

- [x] Windows 특정 경로(`C:\smtp-server\...`) 기본값 제거
- [x] 설정값 필수화 및 유효성 검증 추가
- [x] `application.yml` 예시를 OS 중립(relative path) + env override 중심으로 정리
- [x] README 업데이트 (라이브러리화 목표/모듈 구조/설정 원칙)

---

## 남은 작업 (Remaining Work)

### 1. AUTH/STARTTLS 테스트 보강 (a2) - 추천 다음 작업

**목표**: 인증 및 TLS 관련 핵심 경로의 테스트 커버리지 확보

**TODO**:
- [ ] `AUTH PLAIN` 성공/실패 시나리오 테스트
- [ ] `STARTTLS` 핸드셰이크 성공/실패 테스트
- [ ] TLS upgrade 후 세션 상태 유지 테스트
- [ ] 인증 없이 MAIL FROM 거부 테스트 (requireAuthForMail)
- [ ] STARTTLS 없이 AUTH 시도 거부 테스트

**관련 파일**:
- `src/test/kotlin/...` (테스트 디렉토리 확인 필요)
- `core/src/main/kotlin/.../auth/`
- `core/src/main/kotlin/.../server/SmtpSession.kt`

**우선순위**: HIGH (보안 관련 기능)

---

### 2. 설정 시스템 고도화

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

### 3. 메트릭 및 모니터링 (관측성)

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

### 4. 문서 정리 및 통합

**목표**: 문서 일관성 확보 및 중복 제거

**TODO**:
- [ ] `docs/` 디렉토리 정리:
  - 통합된 ROADMAP.md는 이미 생성됨
  - 중복/구식 문서 제거 검토 필요
- [ ] `docs/THIN_ARCHITECTURE.md` 업데이트 (core 모듈 분리 반영)
- [ ] API 문서화 (KDoc 활용)

**관련 파일**:
- `docs/*.md`
- `READMD.md` (이름 변경 필요: README.md)

**우선순위**: MEDIUM

---

### 5. Spring Boot Starter 분리 (3모듈 구조로 정리)

**목표**: 라이브러리화 최종 형태를 안정화

**TODO**:
- [ ] `kotlin-smtp-spring-boot-starter` 신규 모듈 생성
- [ ] starter에서 `@ConfigurationProperties` + auto-config 제공
- [ ] example app은 가장 마지막(포트폴리오 단계)으로 미룸

---

### 6. Example App (포트폴리오용, 마지막에 진행)

**목표**: 라이브러리(core + starter)를 소비해서 SMTP 서버 앱까지 구성

**TODO**:
- [ ] `kotlin-smtp-example-app` 모듈 생성
- [ ] core + starter 의존
- [ ] 실제 운영/배포는 목적이 아니라 "사용 예시 + 포트폴리오"로 구성

**우선순위**: MEDIUM

---

### 6. CI/CD 파이프라인

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

**옵션 A**: `a2` - AUTH/STARTTLS 테스트 보강
- 보안 관련 기능의 신뢰성 확보
- 회귀 방지

**옵션 B**: `5` - Spring Boot Starter 분리
- core를 라이브러리로 쓰기 쉽게 만듦(자동 설정)

**옵션 C**: `3` - 메트릭 및 모니터링
- 운영 환경에서 필수적인 관측성 확보

### 장기 로드맵

1. **안정화 단계** (현재 ~ 2주)
   - 테스트 보강 (a2)
   - 설정 고도화
   - 메트릭 추가

2. **운영화 단계** (2~4주)
   - Docker/K8s 배포
   - 문서 완성
   - CI/CD

3. **확장 단계** (4주+)
   - PostgreSQL/R2DBC 지원 (auth + 메타데이터)
   - S3 저장소 지원
   - Spring Boot Starter 모듈

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


---

## 연락/질문

- 프로젝트 구조 관련: `kotlin-smtp-core/` 모듈 참조
- 설정 관련: `kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/config/SmtpServerProperties.kt` 및 `docs/application.example.yml` 참조
- 아키텍처 관련: `docs/THIN_ARCHITECTURE.md` 참조

---

*최종 업데이트: 2026-02-02*
*다음 검토 예정: 작업 완료 시마다 업데이트*
