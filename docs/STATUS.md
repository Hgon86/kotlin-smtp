# 진행 상황 및 다음 작업 (Status & Next Steps)

이 문서는 "현재 무엇이 완료되었는지"와 "다음에 무엇을 해야 하는지"를 한 곳에서 추적합니다.

## 현재 상태

- 브랜치: `dev`
- 모듈 구조: `kotlin-smtp-core`(Spring-free) + `kotlin-smtp-spring-boot-starter`(Spring Boot wiring)
- 테스트: `./gradlew test` 통과(최근 변경마다 실행)
- Public API 원칙: `docs/PUBLIC_API_POLICY.md`
- 옵션 모듈 분리(설계): `docs/MODULE_STRATEGY.md`
- relay 모듈 설계 확정: `docs/RELAY_MODULES.md`

결정사항(현재):
- relay(outbound SMTP 전송)는 starter에서 분리해 옵션 모듈로 제공

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
- AUTH/STARTTLS 회귀 테스트 보강
  - STARTTLS 이후 인증 전 `MAIL FROM` 거부(530) 시나리오 고정
  - `AUTH PLAIN` 실패 시 535 응답 시나리오 고정

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

아래는 "범용 라이브러리로 배포 가능한 수준"을 기준으로, 남은 작업을 큰 목표별로 세분화한 목록입니다.

### 라이브러리 경계(중요: 범용 목표)

이 프로젝트의 목표는 "SMTP 서버를 **정확하게 수신**하는 core"와 "바로 실행 가능한 기본 구성을 제공하는 starter"를 분리하는 것입니다.

결정 사항(현재 합의):
- core는 SMTP 수신(프로토콜/세션/TLS/AUTH/프레이밍)과 확장점(SPI)에 집중하고, 인프라(DB/S3/Kafka/MIME 파서)는 강제하지 않습니다.
- 수신 결과물의 1급 산출물은 Raw EML(RFC822 원문)이며, 후처리(첨부 분리/인라인 이미지 처리/검색 인덱싱 등)는 별도 파이프라인(옵션)으로 둡니다.
- starter는 "최소 설정으로 기동" 가능한 기본 조합을 제공하되, 특정 인프라 통합은 옵션 모듈/사용자 구현으로 분리합니다.

- core(`kotlin-smtp-core`)의 책임
  - SMTP 세션/상태머신, 프레이밍(DATA/BDAT), STARTTLS/AUTH, backpressure, timeout 등 **프로토콜 정확성**
  - 호스트가 확장할 수 있도록 최소한의 **SPI(확장점) 인터페이스** 제공
  - 수신 결과물은 원문(raw) RFC822 메시지 스트림과 envelope 메타데이터로 표현(아래 참고)
- starter(`kotlin-smtp-spring-boot-starter`)의 책임
  - core를 자동 설정하여, 필수 설정만 주면 "SMTP 서버가 바로 뜨는" 기본 경험 제공
  - 레퍼런스 구현(로컬 파일 기반 store/spool 등)을 제공하되, 특정 인프라(DB/S3/Kafka 등)는 **옵션**으로 둠
- Non-goals(의도적으로 core 범위 밖)
  - MIME 파싱/첨부파일 분리/인라인 이미지 추출/이미지 서버 업로드 같은 "메일 서비스" 후처리
  - 특정 DB/JPA/R2DBC/클라우드 SDK 종속을 core에 포함

용어 정리(권장):
- **Raw EML**: SMTP로 수신한 RFC822 원문. DATA/BDAT는 대부분 이미 RFC822이므로 별도의 "조립"보다는 BDAT chunk 결합 + 라인엔딩/도트스터핑 정규화가 핵심입니다.
- **Envelope 메타데이터**: MAIL FROM/RCPT TO, remote ip, helo/ehlo, TLS/AUTH 여부, 수신 시각, 크기/해시 등 운영에 필요한 최소 정보.

보안 메모(원문 저장):
- Raw EML 암호화(저장 시점의 at-rest 암호화)는 환경/규제/키관리 방식이 다양하므로 core가 강제하지 않습니다.
- 권장: 스토리지 계층에서 해결(S3 SSE-KMS, 디스크 암호화, DB TDE 등)하거나, starter/옵션 모듈에서 "암호화 MessageStore" 같은 형태로 제공하는 방식을 고려합니다.

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

추가(범용 라이브러리 관점에서 꼭 포함):
- "저장/전달/후처리"를 core에 직접 구현하기보다, host가 구현체를 교체할 수 있도록 **SPI(확장점) 경계**를 명확히 합니다.
- 특히 S3/Kafka/DB 같은 인프라 의존은 core에 넣지 않고, starter 또는 별도 모듈에서 제공하는 방식을 기본으로 합니다.

---

### 1.5) 수신 결과물/저장/이벤트 훅(SPI) 경계 확정(범용 확장)

목표: Raw EML 저장(S3/파일/메모리), 메시지 메타데이터 저장(DB), 후처리 파이프라인(Kafka 등)을 host가 선택/구현할 수 있게 **경계만 제공**합니다.

배경:
- 범용 라이브러리에서는 "DB 저장"이나 "S3 업로드"를 core가 강제하면 안 됩니다(기술 선택/운영 정책이 사용자마다 다름).
- 대신 core는 "수신 결과물"을 표준 형태로 내보내고, starter는 "바로 뜨는 기본 구현"을 제공합니다.

작업:
1. 수신 결과물 모델(문서 + 타입 후보) 정의
   - Envelope 메타: `MAIL FROM`, `RCPT TO`, `remoteAddress`, `helo/ehlo`, `tls/auth` 상태, 수신 시각
   - Raw EML: size, sha256 같은 식별/중복 방지 값(권장)
2. 이벤트 훅(SPI) 범위/호출 시점 최소화(과설계 방지)
   - 훅 종류는 최소로 시작(예: Accepted / Stored / Rejected / SessionEnd 수준)
   - "언제 호출되는지"만 먼저 고정하고, 구현체는 starter/별도 모듈에서 제공(예: DB writer, Kafka publisher)
3. 레퍼런스 구현 제공 범위 결정
   - starter: 로컬 파일 기반 raw 저장 + spool(현 구조 유지)
   - optional: `kotlin-smtp-storage-s3`, `kotlin-smtp-metadata-jdbc`, `kotlin-smtp-event-kafka` 같은 별도 모듈(필요 시)

완료 기준:
- "core public API 후보" 문서에 SPI가 반영되고(`docs/PUBLIC_API_CANDIDATES.md`), core가 인프라 의존 없이 확장 가능
- starter MVP가 "최소 설정으로 기동"되는 것이 문서/테스트로 검증

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

#### Starter MVP: "최소 설정으로 바로 기동"(정확한 기준)

Starter MVP의 정의는 "core + starter 의존" 후, 아래의 **기동 필수 설정만** 맞추면 SMTP 서버가 자동으로 시작되는 것입니다.

기동 필수/권장/선택 설정(기준: `docs/application.example.yml` + `SmtpServerProperties`):

| 구분 | 설정 키 | 단일 포트 모드 | listeners 모드 | 설명 |
|---|---|---:|---:|---|
| 기동 필수 | `smtp.storage.mailboxDir` | O | O | 로컬 메일박스(기본 구현에서 사용). 비워두면 starter가 기동을 거부합니다. |
| 기동 필수 | `smtp.storage.tempDir` | O | O | Raw EML 임시 저장(기본 `FileMessageStore`). 비워두면 starter가 기동을 거부합니다. |
| 기동 필수 | `smtp.storage.listsDir` | O | O | EXPN/메일링리스트 기본 구현에서 사용. 비워두면 starter가 기동을 거부합니다. |
| 기동 필수 | `smtp.spool.dir` | O | O | 스풀/재시도 디렉터리. 비워두면 starter가 기동을 거부합니다. |
| 기동 필수(모드) | `smtp.port` | O | X | `smtp.listeners`가 비어 있을 때 사용(단일 포트 모드). |
| 기동 필수(모드) | `smtp.listeners[].port` | X | O | listeners 모드 선택 시 필수. |
| 권장(운영) | `smtp.hostname` | O | O | 서버 호스트명/배너/DSN 등에 사용. 운영에서는 명시 권장. |
| 권장(보안) | `smtp.rateLimit.*` | O | O | 인터넷 노출 시 권장(기본값 유지 가능). |
| 선택 | `smtp.auth.*` | O | O | AUTH 사용 여부. Submission에서는 활성화 권장. |
| 선택 | `smtp.ssl.*` | O | O | STARTTLS/SMTPS 사용 시 필요(인증서/키). 평문 수신만이면 생략 가능. |
| 선택 | `smtp.proxy.trustedCidrs` | O | O | PROXY protocol을 켜는 리스너가 있을 때만 운영에서 필요. |
| 선택 | `smtp.relay.*` | O | O | 아웃바운드 릴레이(외부 도메인 전달). 기본 비활성 권장. |
| 선택 | `smtp.features.*` | O | O | VRFY/ETRN/EXPN 등. 인터넷 노출 기본값은 off 권장. |

주의:
- listeners 모드를 사용하면, 단일 포트용 `smtp.port`는 무시됩니다(리스너 목록을 기준으로 서버를 생성).
- Starter MVP는 "기동"이 목표이며, S3/Kafka/DB 메타데이터 저장 같은 인프라 통합은 옵션 모듈/사용자 구현으로 분리합니다.

기본 저장/처리 플로우(레퍼런스):
- 수신한 Raw EML을 기본 `MessageStore`(현재는 `FileMessageStore`)로 저장
- 스풀러(`smtp.spool.dir`)로 재시도/전달(릴레이 사용 시) 처리
- (옵션) 외부 인프라(S3/Kafka/DB 메타 저장)는 사용자 구현 또는 별도 모듈로 연결
- (옵션) at-rest 암호화는 스토리지/옵션 모듈에서 처리

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

추가(확장 포인트 변경 시):
- SPI(확장점) 변경은 public API로 취급하고 semver/문서/테스트를 함께 갱신

*최종 업데이트: 2026-02-05*
