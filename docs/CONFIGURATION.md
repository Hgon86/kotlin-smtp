# 설정 가이드

## 목차

1. [기본 설정](#기본-설정)
2. [리스너 설정](#리스너-설정)
3. [TLS 설정](#tls-설정)
4. [스풀러 설정](#스풀러-설정)
5. [인증 설정](#인증-설정)
6. [릴�이 설정](#릴�이-설정)
7. [Rate Limit 설정](#rate-limit-설정)

## 기본 설정

### 최소 설정

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
  sentArchive:
    mode: TRUSTED_SUBMISSION
```

기본 구현에서는 발신 메일 사본을
`smtp.storage.mailboxDir/<owner>/sent/` 경로에 저장합니다.
필요 시 `SentMessageStore` 빈을 교체해 S3/DB 기반으로 확장할 수 있습니다.

저장 소유자 결정:
- 인증 세션: AUTH 사용자 기준 (`authenticatedUsername`)
- 무인증 세션: envelope sender local-part 기준

`smtp.sentArchive.mode`:
- `TRUSTED_SUBMISSION`(기본): AUTH 인증 세션 또는 외부 릴레이 제출 메시지 저장
- `AUTHENTICATED_ONLY`: AUTH 인증 세션만 저장
- `DISABLED`: 보낸 메일함 저장 비활성화

### 전체 설정 예시

`docs/application.example.yml` 파일을 참조하세요.

## 리스너 설정

### 단일 포트 모드 (기본)

```yaml
smtp:
  port: 2525
  hostname: localhost
```

### 다중 포트 모드 (리스너)

```yaml
smtp:
  listeners:
    # MTA 수신 (25/2525 등)
    - port: 2525
      serviceName: ESMTP
      implicitTls: false
      enableStartTls: true
      enableAuth: true
      requireAuthForMail: false
      idleTimeoutSeconds: 300
    
    # Submission (587)
    - port: 587
      serviceName: SUBMISSION
      enableStartTls: true
      enableAuth: true
      requireAuthForMail: true
      idleTimeoutSeconds: 300
    
    # SMTPS (465)
    - port: 465
      serviceName: SMTPS
      implicitTls: true
      enableAuth: true
      requireAuthForMail: true
      idleTimeoutSeconds: 300
```

### 리스너 옵션 설명

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `port` | - | 포트 번호 (필수) |
| `serviceName` | ESMTP | 서비스 배너 이름 |
| `implicitTls` | false | 접속 즉시 TLS 시작 (SMTPS) |
| `enableStartTls` | true | STARTTLS 명령 지원 |
| `enableAuth` | true | AUTH 명령 지원 |
| `requireAuthForMail` | false | MAIL FROM 전 인증 필수 |
| `idleTimeoutSeconds` | 300 | 연결 유휴 타임아웃 (0=무제한) |
| `proxyProtocol` | false | PROXY protocol v1 수신 |

## TLS 설정

### STARTTLS (권장)

```yaml
smtp:
  ssl:
    enabled: true
    certChainFile: /path/to/cert.pem
    privateKeyFile: /path/to/key.pem
    minTlsVersion: TLSv1.2
    handshakeTimeoutMs: 30000
```

### Implicit TLS (SMTPS/465)

```yaml
smtp:
  listeners:
    - port: 465
      implicitTls: true
  ssl:
    enabled: true
    certChainFile: /path/to/cert.pem
    privateKeyFile: /path/to/key.pem
```

### TLS 옵션

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `enabled` | false | TLS 활성화 |
| `certChainFile` | - | 인증서 체인 파일 경로 |
| `privateKeyFile` | - | 개인 키 파일 경로 |
| `minTlsVersion` | TLSv1.2 | 최소 TLS 버전 |
| `handshakeTimeoutMs` | 30000 | 핸드셰이크 타임아웃 |
| `cipherSuites` | [] | 사용할 암호화 스위트 |

## 스풀러 설정

### 기본 설정

```yaml
smtp:
  spool:
    type: auto
    dir: ./data/spool
    maxRetries: 5
    retryDelaySeconds: 60
```

### Redis 백엔드 설정

```yaml
smtp:
  spool:
    type: redis
    dir: ./data/spool
    redis:
      keyPrefix: kotlin-smtp:spool
      maxRawBytes: 26214400
      lockTtlSeconds: 900
```

- `type=auto`는 `StringRedisTemplate` 빈이 있으면 Redis, 없으면 file을 자동 선택합니다.
- `type=redis`일 때 원문/큐/락/메타 상태를 Redis에 저장합니다.
- 배달 시점에만 임시 파일을 생성해 사용 후 정리합니다.
- `StringRedisTemplate` 빈이 없으면 부팅 시 스풀 빈 구성이 실패합니다.
- Redis 단일/클러스터/Sentinel 구성은 사용자 애플리케이션의 Redis 설정을 그대로 사용합니다.

### 고급 설정

```yaml
smtp:
  spool:
    type: auto
    dir: ${SMTP_SPOOL_DIR:./data/spool}
    maxRetries: 5
    retryDelaySeconds: 60
```

### 스풀러 옵션

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `type` | auto | 스풀 저장소 타입 (`auto`, `file`, `redis`) |
| `dir` | - | 스풀 디렉터리 경로 (필수) |
| `maxRetries` | 5 | 최대 재시도 횟수 |
| `retryDelaySeconds` | 60 | 초기 재시도 지연 시간(초) |
| `redis.keyPrefix` | `kotlin-smtp:spool` | Redis 키 접두사 (`type=redis/auto`에서 Redis 선택 시 사용) |
| `redis.maxRawBytes` | `26214400` | Redis에 허용할 원문 최대 바이트 (`type=redis/auto`에서 Redis 선택 시 사용) |
| `redis.lockTtlSeconds` | `900` | Redis 락 TTL(초) (`type=redis/auto`에서 Redis 선택 시 사용) |

### 재시도 정책

- **지수 백오프**: 60초 → 120초 → 240초 → 480초 → 최대 600초
- **지터**: ±20% 랜덤화
- **최대**: 10분 (600초)

## 인증 설정

### 기본 인증

```yaml
smtp:
  auth:
    enabled: true
    required: false
    users:
      user1: password1
      user2: "$2a$10$..."  # BCrypt 해시 지원
```

### Auth Rate Limiting

```yaml
smtp:
  auth:
    rateLimitEnabled: true
    rateLimitMaxFailures: 5
    rateLimitWindowSeconds: 300
    rateLimitLockoutSeconds: 600
```

### 인증 옵션

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `enabled` | true | 인증 활성화 |
| `required` | false | 모든 명령에 인증 필수 |
| `rateLimitEnabled` | true | Rate limiting 활성화 |
| `rateLimitMaxFailures` | 5 | 윈도우 내 최대 실패 횟수 |
| `rateLimitWindowSeconds` | 300 | 측정 윈도우(초) |
| `rateLimitLockoutSeconds` | 600 | 잠금 시간(초) |

## 릴�이 설정

### 기본 설정

```yaml
smtp:
  relay:
    enabled: true
    requireAuthForRelay: true
    allowedClientCidrs:
      - 10.0.0.0/8
      - 192.168.0.0/16
```

### Smart Host 설정

```yaml
smtp:
  relay:
    enabled: true
    requireAuthForRelay: true
    defaultRoute:
      host: smtp.provider.com
      port: 587
      startTlsEnabled: true
      username: relay-user
      password: ${RELAY_PASSWORD}
```

### 도메인별 라우팅

```yaml
smtp:
  relay:
    enabled: true
    routes:
      - domain: example.com
        host: mx1.example.com
        port: 25
        startTlsEnabled: true
      - domain: "*"  # 기본 경로
        host: smtp.backup.com
        port: 587
```

### 아웃바운드 TLS

```yaml
smtp:
  relay:
    outboundTls:
      ports: [25, 587]
      startTlsEnabled: true
      startTlsRequired: false
      checkServerIdentity: true
      trustAll: false  # 개발 환경에서만 true
      trustHosts: []
      connectTimeoutMs: 15000
      readTimeoutMs: 15000
```

### 릴�이 옵션

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `enabled` | false | 릴�이 활성화 |
| `requireAuthForRelay` | true | 릴�이에 인증 필수 |
| `allowedSenderDomains` | [] | 인증 없이 허용할 발신 도메인 |
| `allowedClientCidrs` | [] | 인증 없이 허용할 클라이언트 CIDR |

`allowedSenderDomains`/`allowedClientCidrs`는 둘 다 설정할 수 있으며,
무인증 릴레이 요청은 두 조건을 모두 만족해야 허용됩니다.
더 복잡한 정책(DB 조회/IP 평판 등)이 필요하면 `RelayAccessPolicy` 빈을 직접 구현해 교체할 수 있습니다.

## Rate Limit 설정

### 연결 제한

```yaml
smtp:
  rateLimit:
    maxConnectionsPerIp: 10
    maxMessagesPerIpPerHour: 100
```

### Rate Limit 옵션

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `maxConnectionsPerIp` | 10 | IP당 최대 동시 연결 수 |
| `maxMessagesPerIpPerHour` | 100 | IP당 시간당 최대 메시지 수 |

## 저장소 설정

### 파일 기반 (기본)

```yaml
smtp:
  storage:
    mailboxDir: ./data/mailboxes
    tempDir: ./data/temp
    listsDir: ./data/lists
```

### 환경변수 사용

```yaml
smtp:
  storage:
    mailboxDir: ${SMTP_MAILBOX_DIR:./data/mailboxes}
    tempDir: ${SMTP_TEMP_DIR:./data/temp}
    listsDir: ${SMTP_LISTS_DIR:./data/lists}
```

## PROXY Protocol 설정

```yaml
smtp:
  listeners:
    - port: 2525
      proxyProtocol: true
  
  proxy:
    trustedCidrs:
      - 127.0.0.1/32
      - ::1/128
      - 10.0.0.0/8
      - 172.16.0.0/12
```

## 기능 플래그

```yaml
smtp:
  features:
    vrfyEnabled: false    # VRFY 명령 (보안상 기본 off)
    etrnEnabled: false    # ETRN 명령 (관리용)
    expnEnabled: false    # EXPN 명령 (보안상 기본 off)
```

## 검증

애플리케이션 시작 시 설정 검증이 수행됩니다:

- 필수 경로 존재 여부
- 포트 범위 (0-65535)
- TLS 설정 일관성
- Rate limit 값 유효성

검증 실패 시 애플리케이션은 시작되지 않습니다.
