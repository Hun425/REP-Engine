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
    │   │   ├── ElasticsearchConfig.kt       # ES 연결 설정
    │   │   ├── RedisConfig.kt               # Redis 연결 설정
    │   │   └── DispatcherConfig.kt          # Virtual Thread 설정
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
        └── application.yml
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
}
```

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
        val updated = FloatArray(384) { i ->
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

### 5. DlqProducer.kt (실패 처리)

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
}
```

#### Redis 키 구조

```
user:preference:USER-000001  →  {"preferenceVector":[0.1,0.2,...], "actionCount":15, ...}
user:preference:USER-000002  →  {"preferenceVector":[0.3,0.1,...], "actionCount":8, ...}
user:preference:USER-000003  →  {"preferenceVector":[0.5,0.4,...], "actionCount":23, ...}
```

#### 왜 Redis랑 ES 둘 다 쓰나요?

| | Redis | Elasticsearch |
|---|---|---|
| 역할 | 메인 저장소 | 백업 저장소 |
| 속도 | 0.1ms | 5ms |
| 보존 기간 | 24시간 (TTL) | 영구 |
| 언제 사용? | 평소 | Redis 미스 시 |

---

## 설정 파일 (application.yml)

```yaml
spring:
  kafka:
    consumer:
      group-id: behavior-consumer-group  # 컨슈머 그룹 ID
      max-poll-records: 500              # 한 번에 500개씩 받기
      enable-auto-commit: false          # 수동 커밋 (중요!)

  data:
    redis:
      host: localhost
      port: 6379

elasticsearch:
  host: localhost
  port: 9200

consumer:
  topic: user.action.v1
  dlq-topic: user.action.v1.dlq
  bulk-size: 500          # ES Bulk 크기
  max-retries: 3          # 최대 재시도 횟수
  retry-delay-ms: 1000    # 재시도 대기 시간
```

---

## 메트릭 (모니터링 지표)

이 모듈은 다양한 메트릭을 수집합니다:

| 메트릭 이름 | 의미 |
|------------|------|
| `kafka.consumer.processed` | 처리한 이벤트 수 |
| `kafka.consumer.indexed` | ES에 저장된 이벤트 수 |
| `kafka.consumer.errors` | 오류 발생 수 |
| `kafka.consumer.batch.duration` | 배치 처리 시간 |
| `es.bulk.success` | ES 저장 성공 수 |
| `es.bulk.failed` | ES 저장 실패 수 |
| `preference.update.success` | 취향 업데이트 성공 수 |

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
| 입력 | Kafka 토픽 `user.action.v1` |
| 출력 | ES `user_behavior_index`, Redis 취향 벡터 |
| 배치 크기 | 500개씩 처리 |
| 재시도 | 3회, 지수 백오프 |
| 실패 처리 | DLQ + 파일 백업 |
| 핵심 알고리즘 | EMA (지수 이동 평균) |
