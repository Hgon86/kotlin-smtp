# Kotlin SMTP 문서

## 목차

1. [시작하기](README.md) - 빠른 시작 가이드
2. [아키텍처](ARCHITECTURE.md) - 시스템 아키텍처와 동작 원리
3. [설정 가이드](CONFIGURATION.md) - 상세 설정 옵션
4. [확장하기](EXTENSION.md) - 커스텀 구현 및 확장 방법

## 개요

Kotlin SMTP는 Netty 기반의 Kotlin SMTP 서버 라이브러리입니다.

### 특징

- **Spring-free Core**: 순수 Kotlin으로 작성된 SMTP 엔진
- **Spring Boot 통합**: Starter 모듈로 쉬운 설정
- **SMTPUTF8/IDN 지원**: 국제화된 이메일 주소 처리
- **TLS 지원**: STARTTLS 및 Implicit TLS
- **PROXY Protocol**: 로드밸런서 뒤에서도 원본 IP 확인
- **모듈화**: 필요한 기능만 선택적으로 사용

### 모듈 구조

```
kotlin-smtp/
├── kotlin-smtp-core/                    # Spring-free SMTP 엔진
├── kotlin-smtp-spring-boot-starter/     # Spring Boot 자동 설정
├── kotlin-smtp-relay/                   # 릴레이 API (옵션)
├── kotlin-smtp-relay-jakarta-mail/      # Jakarta Mail 릴레이 구현 (옵션)
└── kotlin-smtp-relay-spring-boot-starter/ # 릴레이 Spring Boot 통합 (옵션)
```

## 빠른 시작

### 1. 의존성 추가

```kotlin
dependencies {
    implementation("io.github.kotlinsmtp:kotlin-smtp-spring-boot-starter:0.1.0")
}
```

### 2. 설정 (application.yml)

```yaml
smtp:
  port: 2525
  hostname: localhost
  routing:
    localDomain: mydomain.com
  storage:
    mailboxDir: ./data/mailboxes
    tempDir: ./data/temp
    listsDir: ./data/lists
  spool:
    dir: ./data/spool
    maxRetries: 5
    retryDelaySeconds: 60
```

### 3. 실행

```bash
./gradlew bootRun
```

## 라이선스

Apache License 2.0
