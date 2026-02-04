# 진행 상황 및 다음 작업 (Status & Next Steps)

이 문서는 "현재 무엇이 완료되었는지"와 "다음에 무엇을 해야 하는지"를 한 곳에서 추적합니다.

## 현재 상태

- 브랜치: `dev`
- 모듈 구조: `kotlin-smtp-core`(Spring-free) + `kotlin-smtp-spring-boot-starter`(Spring Boot wiring)
- 테스트: `./gradlew test` 통과(최근 변경마다 실행)
- Public API 원칙: `docs/PUBLIC_API_POLICY.md`

## 최근 완료된 핵심 작업(안정화/리팩터링)

아래는 "Section 4(Runtime/Netty + Session)" 중심으로 진행한 변경들입니다.

### 보안/안정성

- STARTTLS 업그레이드 안정화
  - STARTTLS pipelining 방지(대기 입력이 있으면 501로 거부)
  - 업그레이드 윈도우에서 raw bytes가 SMTP 디코더로 흘러가는 문제를 게이트로 차단
  - 핸드셰이크 완료를 await 한 뒤 동일한 흐름에서 상태 리셋/플래그 적용
- inbound 이중 버퍼 제거
  - handler mailbox 제거 → 단일 큐로 통합하여 BDAT inflight cap 우회 방지
- 백프레셔(soft throttling)
  - queued bytes 기준 high/low watermark에서 autoRead 토글로 스파이크 드롭 완화
- 예외 메시지 노출 제거
  - DATA/BDAT에서 클라이언트 응답에 내부 예외 메시지 노출하지 않도록 고정 메시지화

### 유지보수성

- 큰 파일 책임 분리
  - `SmtpBackpressureController`로 인바운드 흐름 제어 분리
  - `SmtpTlsUpgradeManager`/`StartTlsInboundGate`로 STARTTLS 전환 분리
  - `BdatStreamingState`로 BDAT 스트리밍 상태 캡슐화
  - `SmtpSessionDataResetter`로 `resetTransaction`의 SessionData 재구성 로직 분리
  - `SmtpStreamingHandlerRunner`로 DATA/BDAT 공통 핸들러 실행/응답 매핑 통합
- `SmtpChannelHandler`의 세션 시작 게이팅을 `SessionStartGate`로 명확화

### 관련 커밋(최근)

- `d73aaab` fix(core): harden STARTTLS upgrade and inbound backpressure
- `9f183cb` test(core): cover STARTTLS handshake timeout
- `924ee23` refactor(core): extract inbound backpressure controller
- `01976c7` refactor(core): extract STARTTLS upgrade manager
- `2dae8cc` refactor(core): unify DATA/BDAT handler execution
- `0b636ee` refactor(core): encapsulate BDAT state and session data reset
- `b7734a8` refactor(core): make session start gating explicit

## 앞으로 해야 할 작업(아주 상세)

아래는 "라이브러리로 배포 가능한 수준"을 기준으로, 남은 작업을 큰 목표별로 세분화한 목록입니다.

---

### 1) Public API 경계 확정(가장 우선)

목표: `kotlin-smtp-core`에서 "호스트가 의존해야 하는 타입"만 public으로 안정화하고, Netty/프레이밍/세션 내부는 internal로 유지합니다.

작업:

1. `docs/PUBLIC_API_CANDIDATES.md`를 실제 코드와 1:1로 정합
   - `SmtpServer.create/builder`를 최상위 엔트리로 고정
   - 확장점 인터페이스 목록 최신화(누락/불필요 제거)
   - internal로 유지해야 하는 구현/Netty 타입 명시
2. "public 패키지" 리스트 확정
   - `io.github.kotlinsmtp.server`의 어떤 타입이 public인지 명시
   - `protocol.handler`의 어떤 타입이 public인지 명시
3. API 변경 규칙/릴리즈 룰 문서 보강
   - semver 적용 범위(코어 vs starter)
   - deprecate 정책(기간/대체 경로)

완료 기준:
- `PUBLIC_API_CANDIDATES.md`의 각 항목이 실제 코드 심볼로 링크/설명 가능
- core에서 "외부가 import할 필요가 없는" 타입이 public으로 남아있지 않음
- `./gradlew test` + (가능하면) `./gradlew check` 통과

---

### 2) Spring Boot Starter 기능/문서 마감

목표: starter만 추가하면 `smtp.*` 설정으로 서버가 뜨는 경험을 제공하고, core는 Spring-free를 유지합니다.

작업:

1. 설정 모델 정리
   - `kotlin-smtp-spring-boot-starter`의 `@ConfigurationProperties`가 core builder 옵션과 1:1 매핑되는지 점검
   - 문서(`docs/application.example.yml`)와 실제 프로퍼티 이름/기본값 정합
2. auto-config 동작 검증(테스트)
   - 최소 설정(포트/호스트/프로토콜 핸들러)에서 서버가 기동되는지
   - STARTTLS/implicit TLS 옵션이 올바르게 반영되는지
3. 운영 가이드 작성
   - TLS 인증서 파일/권한/갱신 관련 권장사항
   - PROXY protocol 사용 시 trusted CIDR 설정 가이드

완료 기준:
- starter 기반 통합 테스트(또는 샘플 앱 없이도 가능한 스모크 테스트) 1~2개 추가
- README의 설정 예시가 실제로 동작

---

### 3) 문서 정리(중복 제거 + 최신화)

목표: "어떤 문서를 먼저 읽어야 하는지"가 명확하고, 서로 모순되는 내용이 없도록 정리합니다.

작업:

1. 문서 언어/스타일 통일
   - 핵심 사용자 문서는 한국어 중심으로(필요하면 병기)
2. `docs/THIN_ARCHITECTURE.md` 최신화
   - 현재 런타임 흐름(단일 inbound 큐, backpressure, STARTTLS manager 등) 반영
3. README 정리
   - `SmtpServer.create(...)` 예시가 실제 API와 일치하도록 유지
   - "runnable example app은 마지막" 원칙을 명확히
4. 문서 엔트리포인트 확정
   - `README.md` → `docs/STATUS.md` → `docs/ROADMAP.md` 순으로 읽으면 전체 파악 가능하도록

완료 기준:
- README/ROADMAP/THIN_ARCHITECTURE 간 모순 없음
- 문서 링크가 모두 유효

---

### 4) 배포 준비(Gradle publish + 라이선스 + 릴리즈 프로세스)

목표: Maven Central(또는 GitHub Packages)로 core/starter를 배포할 수 있는 상태를 만듭니다.

작업:

1. 라이선스/SCM/개발자 정보 추가
   - `LICENSE` 추가
   - README에 라이선스 명시
2. Gradle publish 구성
   - artifactId, group, version 정책 확정
   - POM metadata(license/scm/developers) 설정
3. 버전 정책 문서화
   - semver 적용 범위(특히 core public API)
4. 릴리즈 체크리스트 작성
   - release 브랜치/태그/릴리즈 노트
   - 테스트/빌드/서명/배포 순서

완료 기준:
- 로컬에서 `publishToMavenLocal`이 성공
- README에 "어떻게 설치/사용하는지"가 명확

---

### 5) CI/CD(테스트 자동화)

목표: PR마다 테스트가 자동으로 돌고, 릴리즈 시 배포까지 이어질 기반을 마련합니다.

작업:

1. GitHub Actions 추가
   - Windows/Linux에서 `./gradlew test`
   - 캐시(gradle, kotlin daemon) 적용
2. (선택) 코드 스타일/정적 분석
   - ktlint/detekt 도입 여부 결정
3. (선택) 릴리즈 워크플로
   - 태그 기반 publish

완료 기준:
- 기본 CI 워크플로 1개가 main/dev에 대해 동작

---

### 6) Example App(마지막 단계)

목표: core+starter 소비 예시를 제공하되, 제품 기능을 얹지 않습니다.

작업:

1. 별도 모듈로 분리
   - `kotlin-smtp-example-app`
2. 최소 구성
   - 설정 파일 + 기동 + 로그 확인
   - TLS/STARTTLS/PROXY 사용 예 포함

완료 기준:
- 문서의 설정 예시를 그대로 붙여 넣으면 실행 가능

## 유지보수 체크리스트(작업할 때마다)

- 새 기능/리팩터링마다 `./gradlew test` 실행
- core public API 변경 시:
  - `docs/PUBLIC_API_CANDIDATES.md` 업데이트
  - `docs/PUBLIC_API_POLICY.md` 체크
- 보안 관련 변경(STARTTLS/AUTH/PROXY/RateLimit)은 통합 테스트 추가

*최종 업데이트: 2026-02-04*
