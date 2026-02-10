# 확장 가이드

## 목차

1. [개요](#개요)
2. [MessageStore 구현](#messagestore-구현)
3. [AuthService 구현](#authservice-구현)
4. [DB 기반 저장소](#db-기반-저장소)
5. [S3 기반 저장소](#s3-기반-저장소)
6. [이벤트 훅](#이벤트-훅)

## 개요

Kotlin SMTP는 SPI(Servce Provider Interface) 패턴을 통해 확장 가능합니다.

### 제공하는 SPI

1. **MessageStore** - 메시지 저장소
2. **AuthService** - 인증 서비스
3. **SmtpProtocolHandler** - 트랜잭션 핸들러
4. **SmtpEventHook** - 이벤트 훅

## MessageStore 구현

### 인터페이스

```kotlin
interface MessageStore {
    suspend fun storeRfc822(
        sender: String,
        recipients: List<String>,
        data: InputStream,
        size: Long,
        metadata: MessageMetadata
    ): StoredMessage
}

data class MessageMetadata(
    val messageId: String,
    val authenticated: Boolean,
    val remoteAddress: String,
    val helo: String?
)

data class StoredMessage(
    val id: String,
    val storageUri: String
)
```

### DB 기반 구현 예시

```kotlin
@Component
class DatabaseMessageStore(
    private val messageRepository: MessageRepository,
    private val blobStorage: BlobStorage
) : MessageStore {
    
    override suspend fun storeRfc822(
        sender: String,
        recipients: List<String>,
        data: InputStream,
        size: Long,
        metadata: MessageMetadata
    ): StoredMessage = withContext(Dispatchers.IO) {
        // 1. 원문 저장 (S3/DB/blob)
        val blobId = blobStorage.store(data)
        
        // 2. 메타데이터 저장
        val entity = MessageEntity(
            id = generateId(),
            sender = sender,
            recipients = recipients,
            blobId = blobId,
            size = size,
            messageId = metadata.messageId,
            receivedAt = Instant.now()
        )
        
        messageRepository.save(entity)
        
        StoredMessage(
            id = entity.id,
            storageUri = "db://messages/${entity.id}"
        )
    }
}
```

### Spring Boot 설정

```kotlin
@Configuration
class CustomStoreConfig {
    
    @Bean
    @Primary
    fun messageStore(
        repository: MessageRepository,
        blobStorage: BlobStorage
    ): MessageStore {
        return DatabaseMessageStore(repository, blobStorage)
    }
}
```

## AuthService 구현

### 인터페이스

```kotlin
interface AuthService {
    suspend fun authenticate(credentials: Credentials): AuthResult
    suspend fun validate(username: String): Boolean
}

sealed class AuthResult {
    data class Success(val user: SmtpUser) : AuthResult()
    data class Failure(val reason: String) : AuthResult()
}
```

### JDBC 기반 구현 예시

```kotlin
@Component
class JdbcAuthService(
    private val jdbcTemplate: JdbcTemplate
) : AuthService {
    
    override suspend fun authenticate(credentials: Credentials): AuthResult {
        val user = jdbcTemplate.queryForObject(
            "SELECT username, password_hash FROM users WHERE username = ?",
            credentials.username
        ) { rs, _ ->
            User(
                username = rs.getString("username"),
                passwordHash = rs.getString("password_hash")
            )
        }
        
        return if (user != null && checkPassword(credentials.password, user.passwordHash)) {
            AuthResult.Success(SmtpUser(user.username))
        } else {
            AuthResult.Failure("Invalid credentials")
        }
    }
}
```

## DB 기반 저장소

### 전체 구현 예시

```kotlin
@Entity
@Table(name = "messages")
class MessageEntity(
    @Id
    val id: String,
    val sender: String,
    @ElementCollection
    val recipients: List<String>,
    val blobId: String,
    val size: Long,
    val messageId: String,
    val receivedAt: Instant,
    val status: MessageStatus = MessageStatus.PENDING
)

enum class MessageStatus {
    PENDING, DELIVERED, FAILED
}

@Repository
interface MessageRepository : JpaRepository<MessageEntity, String>

@Component
class JpaMessageStore(
    private val repository: MessageRepository,
    private val blobStorage: BlobStorage
) : MessageStore {
    
    override suspend fun storeRfc822(
        sender: String,
        recipients: List<String>,
        data: InputStream,
        size: Long,
        metadata: MessageMetadata
    ): StoredMessage = withContext(Dispatchers.IO) {
        val blobId = blobStorage.store(data)
        
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            sender = sender,
            recipients = recipients,
            blobId = blobId,
            size = size,
            messageId = metadata.messageId,
            receivedAt = Instant.now()
        )
        
        repository.save(entity)
        
        StoredMessage(
            id = entity.id,
            storageUri = "jpa://${entity.id}"
        )
    }
}
```

### 설정

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/smtp
    username: smtp_user
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
```

## S3 기반 저장소

### 구현 예시

```kotlin
@Component
class S3MessageStore(
    private val s3Client: S3Client,
    private val metadataRepository: MessageMetadataRepository
) : MessageStore {
    
    private val bucketName = "smtp-messages"
    
    override suspend fun storeRfc822(
        sender: String,
        recipients: List<String>,
        data: InputStream,
        size: Long,
        metadata: MessageMetadata
    ): StoredMessage = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val key = "messages/${id.take(2)}/${id.take(4)}/$id.eml"
        
        // S3 업로드
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType("message/rfc822")
                .build(),
            RequestBody.fromInputStream(data, size)
        )
        
        // 메타데이터 저장 (DB)
        val meta = MessageMetadataEntity(
            id = id,
            sender = sender,
            recipients = recipients,
            s3Key = key,
            size = size,
            messageId = metadata.messageId
        )
        metadataRepository.save(meta)
        
        StoredMessage(
            id = id,
            storageUri = "s3://$bucketName/$key"
        )
    }
}
```

### 의존성

```kotlin
dependencies {
    implementation("software.amazon.awssdk:s3:2.20.0")
}
```

## 이벤트 훅

### SmtpEventHook 구현

```kotlin
@Component
class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>
) : SmtpEventHook {
    
    private val objectMapper = ObjectMapper()
    
    override fun onMessageAccepted(event: SmtpMessageAcceptedEvent) {
        val message = objectMapper.writeValueAsString(
            mapOf(
                "type" to "message_accepted",
                "messageId" to event.messageId,
                "sender" to event.sender,
                "recipients" to event.recipients,
                "timestamp" to event.timestamp.toString()
            )
        )
        
        kafkaTemplate.send("smtp-events", event.messageId, message)
    }
    
    override fun onSessionStarted(event: SmtpSessionStartedEvent) {
        // 세션 시작 이벤트 처리
    }
    
    override fun onSessionEnded(event: SmtpSessionEndedEvent) {
        // 세션 종료 이벤트 처리
    }
}
```

### 설정

```kotlin
@Configuration
class EventConfig {
    
    @Bean
    fun smtpEventHooks(
        kafkaPublisher: KafkaEventPublisher,
        metricCollector: MetricCollector
    ): List<SmtpEventHook> {
        return listOf(kafkaPublisher, metricCollector)
    }
}
```

## 파일 기반에서 DB 기반으로 마이그레이션

### 1단계: Dual Write

```kotlin
@Component
class DualWriteMessageStore(
    private val fileStore: FileMessageStore,
    private val dbStore: DatabaseMessageStore
) : MessageStore {
    
    override suspend fun storeRfc822(
        sender: String,
        recipients: List<String>,
        data: InputStream,
        size: Long,
        metadata: MessageMetadata
    ): StoredMessage {
        // 파일과 DB 둘다 저장
        val fileResult = fileStore.storeRfc822(sender, recipients, data, size, metadata)
        val dbResult = dbStore.storeRfc822(sender, recipients, data, size, metadata)
        
        return dbResult
    }
}
```

### 2단계: Read from DB only

마이그레이션 완료 후 DB만 사용하도록 변경.

## 주의사항

### 트랜잭션 처리

```kotlin
@Transactional
suspend fun storeWithMetadata(...) = withContext(Dispatchers.IO) {
    transactionTemplate.execute {
        // DB 작업
    }
}
```

### 예외 처리

```kotlin
override suspend fun storeRfc822(...): StoredMessage {
    return try {
        // 저장 로직
    } catch (e: Exception) {
        log.error(e) { "Failed to store message" }
        throw MessageStoreException("Storage failed", e)
    }
}
```

### 코루틴 컨텍스트

항상 `withContext(Dispatchers.IO)`를 사용하여 블로킹 I/O를 처리하세요.

## 테스트

### Mock 구현

```kotlin
class InMemoryMessageStore : MessageStore {
    private val messages = mutableListOf<StoredMessage>()
    
    override suspend fun storeRfc822(...): StoredMessage {
        val stored = StoredMessage(
            id = UUID.randomUUID().toString(),
            storageUri = "memory://${messages.size}"
        )
        messages.add(stored)
        return stored
    }
}
```

### 통합 테스트

```kotlin
@SpringBootTest
class DatabaseMessageStoreTest {
    
    @Autowired
    lateinit var messageStore: MessageStore
    
    @Test
    fun `should store message`() = runBlocking {
        val result = messageStore.storeRfc822(
            sender = "test@example.com",
            recipients = listOf("recipient@example.com"),
            data = "Test message".byteInputStream(),
            size = 12,
            metadata = MessageMetadata(
                messageId = "<test@msg>",
                authenticated = true,
                remoteAddress = "127.0.0.1",
                helo = "localhost"
            )
        )
        
        assertNotNull(result.id)
    }
}
```
