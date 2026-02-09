# behavior-consumer 모듈 설명서

## 이 모듈이 하는 일 (한 줄 요약)

**Kafka에서 유저 행동 데이터를 받아서 Elasticsearch에 저장하고, 유저 취향 벡터를 업데이트하는 모듈**

---

## 비유로 이해하기

쇼핑몰에서 고객이 뭘 봤는지, 뭘 샀는지 기록하는 **CCTV 분석팀** 같은 거예요.

1. 고객 행동 기록이 들어오면 (Kafka에서 수신)
2. 기록 보관함에 저장하고 (Elasticsearch에 저장)
3. 고객 취향 프로필을 업데이트합니다 (Redis에 취향 벡터 저장)

나중에 "이 고객이 좋아할 만한 상품"을 추천할 때 이 정보를 사용해요!

---

## 파일 구조

```
behavior-consumer/
├── build.gradle.kts
└── src/main/
    ├── kotlin/com/rep/consumer/
    │   ├── BehaviorConsumerApplication.kt   # 앱 시작점
    │   ├── config/
    │   │   ├── ConsumerProperties.kt        # 설정값 클래스
    │   │   ├── KafkaConsumerConfig.kt       # Kafka 수신 설정
    │   │   ├── KafkaProducerConfig.kt       # Kafka 발신 설정 (DLQ용)
    │   │   ├── ElasticsearchConfig.kt       # ES 연결 설정
    │   │   ├── RedisConfig.kt               # Redis 연결 설정
    │   │   ├── DispatcherConfig.kt          # Virtual Thread 설정
    │   │   ├── WebClientConfig.kt           # WebClient 설정 (Embedding API용)
    │   │   ├── EmbeddingProperties.kt       # Embedding 서비스 설정
    │   │   └── SchemaRegistryHealthIndicator.kt  # Schema Registry 헬스체크
    │   ├── client/
    │   │   └── EmbeddingClient.kt           # Embedding Service 호출
    │   ├── listener/
    │   │   └── BehaviorEventListener.kt     # Kafka 이벤트 수신
    │   ├── service/
    │   │   ├── BulkIndexer.kt               # ES 일괄 저장
    │   │   ├── PreferenceUpdater.kt         # 취향 벡터 업데이트
    │   │   ├── PreferenceVectorCalculator.kt # 벡터 계산 로직
    │   │   └── DlqProducer.kt               # 실패 메시지 처리
    │   └── repository/
    │       ├── UserPreferenceRepository.kt  # 유저 취향 저장소
    │       └── ProductVectorRepository.kt   # 상품 벡터 조회
    └── resources/
        ├── application.yml
        └── logback-spring.xml               # 로깅 설정
```

---

## 전체 동작 흐름 (꼭 이해하세요!)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         전체 처리 흐름                                   │
└─────────────────────────────────────────────────────────────────────────┘

         ┌──────────────────┐
         │  Kafka 토픽      │
         │ user.action.v1   │
         └────────┬─────────┘
                  │
                  │ 1. 메시지 500개씩 배치로 수신
                  ▼
         ┌──────────────────┐
         │ BehaviorEvent    │
         │ Listener         │
         └────────┬─────────┘
                  │
        ┌─────────┴─────────┐
        │                   │
        ▼                   ▼
┌───────────────┐   ┌───────────────┐
│ BulkIndexer   │   │ Preference    │
│ (ES 저장)     │   │ Updater       │
└───────┬───────┘   └───────┬───────┘
        │                   │
        ▼                   │
┌───────────────┐           │
│ Elasticsearch │           │
│ user_behavior │           │
│ _index        │           │
└───────────────┘           │
                            │
              ┌─────────────┴─────────────┐
              │                           │
              ▼                           ▼
      ┌───────────────┐          ┌───────────────┐
      │ ProductVector │          │ UserPreference│
      │ Repository    │          │ Repository    │
      │ (상품 벡터    │          │ (취향 저장)   │
      │  조회)        │          │               │
      └───────┬───────┘          └───────┬───────┘
              │                           │
              ▼                           ▼
      ┌───────────────┐          ┌───────────────┐
      │ Elasticsearch │          │    Redis      │
      │ product_index │          │ (Primary)     │
      └───────────────┘          └───────┬───────┘
                                         │
                                         ▼
                                 ┌───────────────┐
                                 │ Elasticsearch │
                                 │ (Backup)      │
                                 └───────────────┘
```

---

## 핵심 파일 상세 설명

### 1. BehaviorEventListener.kt (이벤트 수신)

**역할**: Kafka에서 메시지를 받아서 처리하는 입구

```kotlin
@Component
class BehaviorEventListener(
    private val bulkIndexer: BulkIndexer,
    private val preferenceUpdater: PreferenceUpdater
) {

    @KafkaListener(topics = ["user.action.v1"])
    fun consume(
        records: List<ConsumerRecord<String, UserActionEvent>>,  // 500개씩 받음
        acknowledgment: Acknowledgment
    ) {
        // 1. 이벤트 추출
        val events = records.map { it.value() }

        // 2. ES에 저장 (완료될 때까지 기다림)
        val indexedCount = bulkIndexer.indexBatchSync(events)

        // 3. 유저 취향 벡터 업데이트
        preferenceUpdater.updatePreferencesBatch(events)

        // 4. 다 처리했으면 Kafka에 "처리 완료" 알림
        acknowledgment.acknowledge()
    }
}
```

#### 중요한 개념: acknowledgment (확인 응답)

**비유**: 택배 수령 서명

```
1. 택배 기사가 택배 전달 (Kafka가 메시지 전달)
2. 서명 안 하면 → 택배 기사가 계속 들고 있음 (재전송)
3. 서명하면 → 택배 전달 완료! (커밋)

우리 코드:
- ES 저장 성공 → acknowledgment.acknowledge() 호출 (서명!)
- ES 저장 실패 → acknowledge 안 함 → 나중에 다시 받음
```

이렇게 하면 **메시지가 절대 유실되지 않아요!**

---

### 2. BulkIndexer.kt (ES 일괄 저장)

**역할**: 여러 이벤트를 한 번에 Elasticsearch에 저장

```kotlin
@Component
class BulkIndexer(
    private val esClient: ElasticsearchClient,
    private val dlqProducer: DlqProducer
) {
    suspend fun indexBatchSync(events: List<UserActionEvent>): Int {
        // 최대 3번 시도
        repeat(3) { attempt ->
            try {
                // 1. Bulk Request 만들기
                val bulkRequest = buildBulkRequest(events)

                // 2. ES에 전송
                val response = esClient.bulk(bulkRequest)

                // 3. 결과 처리
                return handleBulkResponse(response, events)

            } catch (e: Exception) {
                // 실패하면 대기 후 재시도
                // 1초 → 2초 → 4초 (지수 백오프)
                delay(1000L * (1L shl attempt))
            }
        }

        // 3번 다 실패하면 DLQ로 보냄
        sendToDlq(events)
        return 0
    }
}
```

#### Bulk API가 뭔가요?

**비유**: 편지 보내기

```
방법 1: 편지 100통을 1통씩 우체국 가서 보냄
        → 우체국 100번 왕복 (느림!)

방법 2: 편지 100통을 한 봉투에 담아서 한 번에 보냄
        → 우체국 1번만 (빠름!)

Bulk API = 방법 2
```

#### 지수 백오프(Exponential Backoff)가 뭔가요?

```
1차 시도 실패 → 1초 대기 → 재시도
2차 시도 실패 → 2초 대기 → 재시도
3차 시도 실패 → 4초 대기 → 재시도 (또는 포기)

왜 이렇게 하나요?
- 서버가 바쁠 때 바로 재시도하면 더 바빠짐
- 점점 오래 기다리면서 서버가 회복할 시간을 줌
```

---

### 3. PreferenceUpdater.kt (취향 업데이트)

**역할**: 유저의 취향 벡터를 업데이트

```kotlin
@Component
class PreferenceUpdater(
    private val productVectorRepository: ProductVectorRepository,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val preferenceVectorCalculator: PreferenceVectorCalculator
) {
    suspend fun updatePreference(event: UserActionEvent): Boolean {
        val userId = event.userId
        val productId = event.productId
        val actionType = event.actionType

        // 1. 상품 벡터 조회 (ES에서)
        val productVector = productVectorRepository.getProductVector(productId)
        if (productVector == null) return true  // 상품 없으면 스킵

        // 2. 현재 유저 취향 벡터 조회 (Redis에서)
        val currentPreference = userPreferenceRepository.get(userId)

        // 3. EMA로 새 취향 벡터 계산
        val updatedVector = preferenceVectorCalculator.update(
            currentPreference = currentPreference,
            newProductVector = productVector,
            actionType = actionType
        )

        // 4. 저장 (Redis + ES 백업)
        userPreferenceRepository.save(userId, updatedVector)

        return true
    }

    /**
     * 배치 처리: 여러 이벤트를 효율적으로 처리
     */
    suspend fun updatePreferencesBatch(events: List<UserActionEvent>): Int {
        // 1. 유저별로 이벤트 그룹화
        val eventsByUser = events.groupBy { it.userId.toString() }

        for ((userId, userEvents) in eventsByUser) {
            // 2. 해당 유저의 모든 상품 벡터 한 번에 조회
            val productIds = userEvents.map { it.productId.toString() }.distinct()
            val productVectors = productVectorRepository.getProductVectors(productIds)

            // 3. 유저별 Mutex로 직렬화
            getUserLock(userId).withLock {
                var currentPreference = userPreferenceRepository.get(userId)

                // 4. 이벤트 순서대로 취향 벡터 갱신
                for (event in userEvents) {
                    val productVector = productVectors[event.productId.toString()]
                    if (productVector != null) {
                        currentPreference = calculator.update(currentPreference, productVector, event.actionType)
                    }
                }

                // 5. 최종 결과 저장 (한 번만)
                userPreferenceRepository.save(userId, currentPreference!!, actionCount)
            }
        }
    }
}
```

#### 배치 처리 vs 단건 처리

| 항목 | 단건 처리 | 배치 처리 |
|------|----------|----------|
| 상품 벡터 조회 | 이벤트당 1회 | 유저당 1회 (mget) |
| Redis 저장 | 이벤트당 1회 | 유저당 1회 |
| Lost Update 방지 | 유저별 Mutex | 유저별 Mutex |

> 배치 처리는 같은 유저의 여러 이벤트를 그룹화하여 I/O를 최소화합니다.

#### 처리 흐름 그림

```
유저가 "가죽 지갑" 클릭
          │
          ▼
┌─────────────────────────────────┐
│ 1. 상품 벡터 조회               │
│    productVector = [0.1, 0.3, ...] │
└─────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────┐
│ 2. 현재 취향 벡터 조회          │
│    currentPref = [0.2, 0.1, ...] │
│    (없으면 null)                │
└─────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────┐
│ 3. EMA 계산                     │
│    CLICK이니까 α = 0.3         │
│                                 │
│    새 취향 = 기존 × 0.7        │
│            + 상품 × 0.3        │
└─────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────┐
│ 4. 저장                         │
│    Redis: 즉시 저장             │
│    ES: 비동기 백업              │
└─────────────────────────────────┘
```

---

### 4. PreferenceVectorCalculator.kt (벡터 계산)

**역할**: EMA(지수 이동 평균)로 취향 벡터 계산

```kotlin
@Component
class PreferenceVectorCalculator {

    companion object {
        // 행동별 가중치 (얼마나 취향에 반영할지)
        const val ALPHA_PURCHASE = 0.5f      // 구매: 가장 강함
        const val ALPHA_ADD_TO_CART = 0.3f   // 장바구니: 강함
        const val ALPHA_CLICK = 0.3f         // 클릭: 중간
        const val ALPHA_SEARCH = 0.2f        // 검색: 중간
        const val ALPHA_VIEW = 0.1f          // 조회: 약함
        const val ALPHA_WISHLIST = 0.1f      // 찜: 약함
    }

    fun update(
        currentPreference: FloatArray?,  // 현재 취향
        newProductVector: FloatArray,    // 상품 벡터
        actionType: String               // 행동 타입
    ): FloatArray {

        val alpha = getAlpha(actionType)  // 가중치 가져오기

        // 신규 유저면 상품 벡터가 곧 취향
        if (currentPreference == null) {
            return newProductVector.normalize()
        }

        // 기존 유저면 EMA 계산
        // 새 취향 = 기존 × (1-α) + 상품 × α
        val updated = FloatArray(768) { i ->
            currentPreference[i] * (1 - alpha) + newProductVector[i] * alpha
        }

        return updated.normalize()  // 정규화 (길이를 1로)
    }
}
```

#### EMA 공식 쉽게 이해하기

```
예시: 유저가 "가죽 지갑"을 클릭함 (CLICK, α=0.3)

기존 취향 벡터: [0.5, 0.3, 0.2, ...]  (화장품 쪽)
가죽 지갑 벡터: [0.1, 0.8, 0.1, ...]  (가죽 제품 쪽)

새 취향 = 기존 × 0.7 + 가죽지갑 × 0.3

[0] = 0.5 × 0.7 + 0.1 × 0.3 = 0.35 + 0.03 = 0.38
[1] = 0.3 × 0.7 + 0.8 × 0.3 = 0.21 + 0.24 = 0.45
[2] = 0.2 × 0.7 + 0.1 × 0.3 = 0.14 + 0.03 = 0.17

새 취향 벡터: [0.38, 0.45, 0.17, ...]  (가죽 제품 쪽으로 살짝 이동!)
```

**핵심**: 행동할수록 그 상품 방향으로 취향이 조금씩 이동!

---

### 5. EmbeddingClient.kt (Embedding 서비스 호출)

**역할**: Python Embedding Service를 호출하여 텍스트를 768차원 벡터로 변환

```kotlin
@Component
class EmbeddingClient(
    private val embeddingWebClient: WebClient
) {
    companion object {
        const val QUERY_PREFIX = "query: "      // 검색 쿼리용 (유저 취향)
        const val PASSAGE_PREFIX = "passage: "  // 문서용 (상품 정보)
    }

    /**
     * 텍스트 목록을 벡터로 변환 (배치)
     */
    suspend fun embed(texts: List<String>, prefix: String = QUERY_PREFIX): List<FloatArray>?

    /**
     * 단일 텍스트를 벡터로 변환
     */
    suspend fun embedSingle(text: String, prefix: String = QUERY_PREFIX): FloatArray?

    /**
     * 헬스체크
     */
    suspend fun healthCheck(): Boolean
}
```

#### 요청/응답 데이터

```kotlin
// 요청
data class EmbedRequest(
    val texts: List<String>,
    val prefix: String = "query: "  // e5 모델용 prefix
)

// 응답
data class EmbedResponse(
    val embeddings: List<List<Float>>,  // 각 텍스트의 벡터
    val dims: Int = 768                  // 벡터 차원
)
```

#### e5 모델의 prefix 사용

| prefix | 용도 | 예시 |
|--------|------|------|
| `query: ` | 검색 쿼리, 유저 취향 | "query: 가죽 지갑" |
| `passage: ` | 문서, 상품 정보 | "passage: 고급 소가죽 지갑" |

> multilingual-e5-base 모델은 prefix를 사용하여 검색 쿼리와 문서를 구분합니다.
> 이를 통해 비대칭 검색(유저 취향 → 상품 매칭)의 정확도가 향상됩니다.

---

### 6. DlqProducer.kt (실패 처리)

**역할**: 처리 실패한 메시지를 Dead Letter Queue로 보냄

```kotlin
@Component
class DlqProducer(
    private val kafkaTemplate: KafkaTemplate<String, UserActionEvent>
) {
    fun sendSync(event: UserActionEvent): Boolean {
        try {
            // DLQ 토픽으로 전송
            kafkaTemplate.send("user.action.v1.dlq", event.userId, event).get()
            return true
        } catch (e: Exception) {
            // DLQ 전송도 실패하면 파일에 기록 (최후의 수단)
            writeToFailedEventsFile(event)
            return false
        }
    }

    private fun writeToFailedEventsFile(event: UserActionEvent) {
        // logs/failed_events_2024-01-01.log 파일에 저장
        val logEntry = "${Instant.now()}|${event.traceId}|${event.userId}|..."
        File("logs/failed_events_${LocalDate.now()}.log").appendText(logEntry)
    }
}
```

#### DLQ(Dead Letter Queue)란?

**비유**: 배달 불가 우편함

```
일반 우편:  집 주소로 배달 시도
           ↓
        배달 성공? → 끝!
           ↓ (실패)
        재시도 3번
           ↓ (또 실패)
        "배달 불가 우편함"에 보관

DLQ:      Kafka 메시지 수신
           ↓
        처리 성공? → 끝!
           ↓ (실패)
        재시도 3번
           ↓ (또 실패)
        DLQ 토픽에 보관 → 나중에 수동 처리
```

---

### 6. UserPreferenceRepository.kt (저장소)

**역할**: 유저 취향 벡터 저장/조회 (Redis + ES)

```kotlin
@Repository
class UserPreferenceRepository(
    private val redisTemplate: ReactiveRedisTemplate<String, String>,
    private val esClient: ElasticsearchClient
) {
    companion object {
        private const val KEY_PREFIX = "user:preference:"
        private const val ES_INDEX = "user_preference_index"
        private val TTL = Duration.ofHours(24)  // 24시간 후 자동 삭제
    }

    suspend fun save(userId: String, vector: FloatArray, actionCount: Int) {
        // 1. Redis에 저장 (Primary)
        val key = "$KEY_PREFIX$userId"
        redisTemplate.opsForValue()
            .set(key, vectorToJson(vector), TTL)
            .awaitSingle()

        // 2. ES에 백업 (비동기, 실패해도 OK)
        esBackupScope.launch {
            saveToEs(userId, vector, actionCount)
        }
    }

    suspend fun get(userId: String): FloatArray? {
        // 1. Redis에서 조회
        val cached = redisTemplate.opsForValue()
            .get("$KEY_PREFIX$userId")
            .awaitSingleOrNull()

        if (cached != null) {
            return parseVector(cached)  // Redis에 있으면 바로 반환
        }

        // 2. Redis에 없으면 ES에서 찾기
        return getFromEs(userId)?.also { (vector, actionCount) ->
            // ES에서 찾으면 Redis에 다시 캐싱
            save(userId, vector, actionCount)
        }?.first
    }

    /**
     * 유저 취향 데이터 전체 조회 (벡터 + 메타데이터)
     */
    suspend fun getWithMetadata(userId: String): UserPreferenceData? {
        val cached = redisTemplate.opsForValue().get("$KEY_PREFIX$userId").awaitSingleOrNull()
        return cached?.let { objectMapper.readValue<UserPreferenceData>(it) }
    }
}
```

#### Redis 키 구조

```
user:preference:USER-000001  →  {"preferenceVector":[0.1,0.2,...], "actionCount":15, "updatedAt":1705123456789}
user:preference:USER-000002  →  {"preferenceVector":[0.3,0.1,...], "actionCount":8, "updatedAt":1705123456790}
user:preference:USER-000003  →  {"preferenceVector":[0.5,0.4,...], "actionCount":23, "updatedAt":1705123456791}
```

#### UserPreferenceData 구조

```kotlin
data class UserPreferenceData(
    val preferenceVector: List<Float>,  // 768차원 취향 벡터
    val actionCount: Int,               // 누적 행동 수
    val updatedAt: Long,                // 마지막 업데이트 타임스탬프 (밀리초)
    val version: Long                   // 버전 (Optimistic Locking용)
)
```

> `updatedAt`은 ES 백업 시 External Versioning에 사용됩니다.
> `version`은 Optimistic Locking에 사용되어 동시 업데이트 시 race condition을 방지합니다.
> 오래된 데이터가 최신 데이터를 덮어쓰지 않도록 보장합니다.

#### actionCount 초기화 로직

```kotlin
// PreferenceUpdater.kt
val currentData = userPreferenceRepository.getWithMetadata(userId)
var actionCount = currentData?.actionCount ?: 0  // 신규 유저: 0에서 시작

for (event in userEvents) {
    // 이벤트 처리 성공 시
    actionCount++  // 각 이벤트마다 1 증가
}

userPreferenceRepository.save(userId, vector, actionCount)
```

- **신규 유저**: `actionCount = 0`에서 시작, 첫 번째 이벤트 처리 후 `1`
- **기존 유저**: 저장된 `actionCount`에서 계속 증가

#### 왜 Redis랑 ES 둘 다 쓰나요?

| | Redis | Elasticsearch |
|---|---|---|
| 역할 | 메인 저장소 | 백업 저장소 |
| 속도 | 0.1ms | 5ms |
| 보존 기간 | 24시간 (TTL) | 영구 |
| 언제 사용? | 평소 | Redis 미스 시 |

---

## 동시성 제어 (Lost Update 방지)

### 유저별 Mutex

동일 유저의 취향 벡터가 동시에 업데이트되면 Lost Update 문제 발생.
유저별 Mutex로 직렬화하여 해결:

```kotlin
// PreferenceUpdater.kt
private val userLocks = ConcurrentHashMap<String, Mutex>()

private fun getUserLock(userId: String): Mutex =
    userLocks.computeIfAbsent(userId) { Mutex() }

suspend fun updatePreference(userId: String, event: UserActionEvent) {
    val lock = getUserLock(userId)
    lock.withLock {
        // 1. 현재 취향 조회
        val current = userPreferenceRepository.get(userId)
        // 2. EMA 계산
        val updated = calculator.update(current, productVector, event.actionType)
        // 3. 저장
        userPreferenceRepository.save(userId, updated, actionCount)
    }
}
```

**효과**: 동일 유저에 대한 업데이트가 순차적으로 처리됨

### External Versioning (ES 백업)

ES 백업 시 오래된 데이터가 최신 데이터를 덮어쓰지 않도록 External Versioning 사용:

```kotlin
// UserPreferenceRepository.kt
esClient.index { idx ->
    idx.index(ES_INDEX)
        .id(userId)
        .document(document)
        .versionType(VersionType.External)
        .version(updatedAt)  // updatedAt 타임스탬프를 버전으로 사용
}
```

**효과**: 네트워크 지연으로 구버전 데이터가 뒤늦게 도착해도 무시됨

---

## DLQ 파일 백업 (최후의 수단)

DLQ 전송도 실패하면 로컬 파일에 기록:

```kotlin
// DlqProducer.kt
companion object {
    private const val MAX_FILE_SIZE = 10 * 1024 * 1024L  // 10MB
}

private fun writeToFailedEventsFile(event: UserActionEvent) {
    val logFile = File("logs/failed_events_${LocalDate.now()}.log")

    // 파일 크기 제한 체크
    if (logFile.exists() && logFile.length() >= MAX_FILE_SIZE) {
        // 타임스탬프 기반 백업 생성
        val backupName = "failed_events_${LocalDate.now()}_${System.currentTimeMillis()}.log"
        logFile.renameTo(File("logs/$backupName"))
    }

    // JSON 형식으로 기록
    val entry = objectMapper.writeValueAsString(mapOf(
        "timestamp" to Instant.now().toString(),
        "traceId" to event.traceId,
        "userId" to event.userId,
        "productId" to event.productId,
        "actionType" to event.actionType.toString()
    ))
    logFile.appendText("$entry\n")
}
```

### 파일 로테이션 정책

| 조건 | 동작 |
|------|------|
| 일별 | 새 파일 생성 (`failed_events_2024-01-15.log`) |
| 10MB 초과 | 타임스탬프 백업 후 새 파일 생성 |

---

## 설정 파일 (application.yml)

### 포트 설정

| 환경 | 포트 | 용도 |
|------|------|------|
| Docker | 8081 | Actuator 메트릭 엔드포인트 노출 |

> **참고**: 이 모듈은 Kafka Consumer로 HTTP 요청을 받지 않습니다.
> 포트는 Prometheus가 메트릭을 수집하기 위한 Actuator 엔드포인트 용도입니다.

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: behavior-consumer-group  # 컨슈머 그룹 ID
      max-poll-records: 500              # 한 번에 500개씩 받기
      enable-auto-commit: false          # 수동 커밋 (중요!)
      properties:
        schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

elasticsearch:
  host: ${ELASTICSEARCH_HOST:localhost}
  port: ${ELASTICSEARCH_PORT:9200}

# Embedding Service (상품 벡터 생성)
embedding:
  service:
    url: ${EMBEDDING_SERVICE_URL:http://localhost:8000}
    timeout-ms: 5000    # 타임아웃 5초
    batch-size: 32      # 배치 요청 크기

# Consumer 설정 (환경변수로 오버라이드 가능)
consumer:
  topic: user.action.v1
  dlq-topic: user.action.v1.dlq
  bulk-size: 500                               # ES Bulk 크기
  concurrency: ${CONSUMER_CONCURRENCY:3}       # 동시 처리 스레드 수
  max-retries: ${CONSUMER_MAX_RETRIES:3}       # 최대 재시도 횟수
  retry-delay-ms: ${CONSUMER_RETRY_DELAY_MS:1000}  # 재시도 대기 시간
  vector-dimensions: ${VECTOR_DIMS:768}  # multilingual-e5-base 벡터 차원

# Actuator (메트릭 수집용)
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics

---
# Docker 프로파일
spring:
  config:
    activate:
      on-profile: docker

server:
  port: 8081  # Docker 환경에서 Actuator 포트
```

---

## ProductVectorRepository (상품 벡터 조회)

**역할**: ES product_index에서 상품 벡터 조회

```kotlin
@Repository
class ProductVectorRepository(private val esClient: ElasticsearchClient) {

    /** 단일 상품 벡터 조회 */
    fun getProductVector(productId: String): FloatArray?

    /** 여러 상품 벡터 일괄 조회 (mget) */
    fun getProductVectors(productIds: List<String>): Map<String, FloatArray>
}
```

### 벡터 차원 검증

상품 벡터 조회 시 **벡터 차원 검증**을 수행합니다 (설정값: `consumer.vector-dimensions`):

```kotlin
if (vector.size != EXPECTED_DIMENSIONS) {
    log.warn { "Product $productId has invalid vector dimension: ${vector.size}" }
    return null  // 잘못된 차원의 벡터는 무시
}
```

### 배치 조회 (mget)

```kotlin
// 단건 조회 (get)
val vector = getProductVector("PROD-001")

// 배치 조회 (mget) - 네트워크 왕복 1회로 여러 상품 조회
val vectors = getProductVectors(listOf("PROD-001", "PROD-002", "PROD-003"))
// 결과: {"PROD-001": [...], "PROD-002": [...], "PROD-003": [...]}
```

> 배치 처리 시 `getProductVectors()`로 유저의 모든 상품 벡터를 한 번에 조회하여 I/O 최소화.

---

## 메트릭 (모니터링 지표)

이 모듈은 다양한 메트릭을 수집합니다:

### Kafka Consumer 메트릭

| 메트릭 이름 | 의미 |
|------------|------|
| `kafka.consumer.processed` | 처리한 이벤트 수 |
| `kafka.consumer.indexed` | ES에 저장된 이벤트 수 |
| `kafka.consumer.errors` | 오류 발생 수 |
| `kafka.consumer.batch.duration` | 배치 처리 시간 |
| `kafka.consumer.batch.size` | 배치당 이벤트 수 |

### ES Bulk Indexing 메트릭

| 메트릭 이름 | 의미 |
|------------|------|
| `es.bulk.success` | ES 저장 성공 수 |
| `es.bulk.failed` | ES 저장 실패 수 |
| `es.bulk.batch.failed` | 전체 배치 실패 수 |
| `es.bulk.retry` | 재시도 횟수 |
| `es.bulk.duration` | Bulk 요청 소요 시간 |

### 취향 벡터 업데이트 메트릭

| 메트릭 이름 | 의미 |
|------------|------|
| `preference.update.success` | 취향 업데이트 성공 수 |
| `preference.update.skipped` | 상품 벡터 없어서 스킵된 수 |
| `preference.update.failed` | 취향 업데이트 실패 수 |

### DLQ 메트릭

| 메트릭 이름 | 의미 |
|------------|------|
| `kafka.dlq.sent` | DLQ로 전송된 메시지 수 |
| `kafka.dlq.failed` | DLQ 전송 실패 (파일로 백업) 수 |

---

## 실행 방법

```bash
# 인프라 시작
cd docker
docker-compose up -d

# 인덱스 생성
./init-indices.sh

# behavior-consumer 실행
cd ..
./gradlew :behavior-consumer:bootRun
```

---

## 핵심 요약

| 항목 | 내용 |
|------|------|
| 역할 | Kafka → ES 저장 + 취향 벡터 업데이트 |
| 포트 | 8081 (Docker, Actuator 메트릭용) |
| 입력 | Kafka 토픽 `user.action.v1` |
| 출력 | ES `user_behavior_index`, Redis 취향 벡터 |
| 배치 크기 | 500개씩 처리 |
| 재시도 | 3회, 지수 백오프 |
| 실패 처리 | DLQ + 파일 백업 |
| 핵심 알고리즘 | EMA (지수 이동 평균) |
