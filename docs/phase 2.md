# Phase 2: Data Pipeline & Elasticsearch Indexing

본 문서는 Kafka 토픽의 이벤트를 소비하여 Elasticsearch에 효율적으로 적재하는 'Event Consumer' 레이어의 상세 설계를 다룹니다.

## 1. 컨슈머 아키텍처 (Consumer Architecture)

### 1.1 전체 흐름

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Behavior Consumer Service                        │
│                                                                          │
│  ┌──────────────┐         ┌──────────────────────────────────────────┐  │
│  │    Kafka     │         │           Bulk Processor                 │  │
│  │   Batch      │────────▶│  ┌──────────────────┐                   │  │
│  │   Listener   │         │  │ ES Bulk Indexer  │ (동기식 처리)      │  │
│  └──────────────┘         │  └────────┬─────────┘                   │  │
│         │                  │           │                              │  │
│         │ (실패 시)        │  ┌────────▼─────────┐                   │  │
│         ▼                  │  │ Preference       │ (best-effort)     │  │
│  ┌──────────────┐         │  │ Vector Updater   │                   │  │
│  │     DLQ      │         │  └──────────────────┘                   │  │
│  │   Producer   │         └──────────────────────────────────────────┘  │
│  └──────────────┘                                                       │
└─────────────────────────────────────────────────────────────────────────┘
```

> **Note:** 실제 구현에서는 Kafka Batch Listener가 직접 BulkIndexer를 호출하는 동기식 방식입니다.
> 이 방식이 오프셋 커밋과 ES 저장의 일관성을 더 명확하게 보장합니다.

### 1.2 핵심 구성 요소

| 컴포넌트 | 역할 | 기술 |
|---------|------|------|
| Kafka Batch Listener | `user.action.v1` 토픽 구독, 배치 단위 메시지 수신 | Spring Kafka |
| Bulk Indexer | ES Bulk API로 동기식 일괄 저장, 재시도 로직 포함 | Virtual Threads |
| DLQ Producer | 처리 실패 메시지를 Dead Letter Queue로 전송 | Kafka Producer |
| Preference Updater | 유저 취향 벡터 계산 및 Redis 저장 (best-effort) | Redis Client |


## 2. Elasticsearch 인덱스 설계 (Index Mapping)

### 2.1 `user_behavior_index` 매핑

```json
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 0,
    "refresh_interval": "5s",
    "index.mapping.total_fields.limit": 100
  },
  "mappings": {
    "properties": {
      "traceId": { "type": "keyword" },
      "userId": { "type": "keyword" },
      "productId": { "type": "keyword" },
      "category": { "type": "keyword" },
      "actionType": { "type": "keyword" },
      "metadata": { "type": "object", "enabled": false },
      "timestamp": { "type": "date" }
    }
  }
}
```

### 2.2 설정값 결정 근거

| 설정 | 값 | 근거 |
|-----|-----|------|
| `number_of_shards` | 3 | 일별 예상 문서 수 ~1000만. 샤드당 ~300만 문서로 검색 성능 최적화 |
| `number_of_replicas` | 0 | 개발 환경. 프로덕션에서는 1로 설정 |
| `refresh_interval` | 5s | Near-realtime 검색 불필요. 인덱싱 성능 우선 |


## 3. 실무급 구현 전략 (Implementation Strategy)

### 3.1 Elasticsearch Java API Client

> **주의:** `RestHighLevelClient`는 Elasticsearch 7.15부터 deprecated되었습니다. ES 8.x에서는 새로운 **Elasticsearch Java API Client**를 사용합니다.

```kotlin
@Configuration
class ElasticsearchConfig {

    @Bean
    fun elasticsearchClient(): ElasticsearchClient {
        val restClient = RestClient.builder(
            HttpHost("localhost", 9200)
        ).build()

        val transport = RestClientTransport(restClient, JacksonJsonpMapper())
        return ElasticsearchClient(transport)
    }
}
```

### 3.2 Bulk Indexing 구현

#### 설정값 결정 근거

| 파라미터 | 값 | 근거 |
|---------|-----|------|
| `bulkSize` | 500 | ES 권장: 5-15MB per bulk. 이벤트 평균 1KB × 500 = 500KB. 안전 마진 확보 |
| `flushInterval` | 1초 | 지연 시간과 처리량 균형. 1초 이내 인덱싱 보장 |
| `maxRetries` | 3 | 일시적 네트워크 오류 대응. 3회 초과 시 DLQ 전송 |

#### 구현 코드

> **Note:** 실제 구현은 Channel 기반 비동기 버퍼링 대신 **동기식 배치 처리**를 사용합니다.
> Kafka Batch Listener가 직접 `indexBatchSync()`를 호출하여 오프셋 커밋 전 저장 완료를 보장합니다.

```kotlin
@Component
class BulkIndexer(
    private val esClient: ElasticsearchClient,
    private val dlqProducer: DlqProducer,
    private val properties: ConsumerProperties,
    private val meterRegistry: MeterRegistry
) {
    @Value("\${elasticsearch.index.user-behavior:user_behavior_index}")
    private lateinit var indexName: String

    private lateinit var bulkSuccessCounter: Counter
    private lateinit var bulkFailedCounter: Counter
    private lateinit var retryCounter: Counter
    private lateinit var bulkIndexTimer: Timer

    /**
     * 이벤트 배치를 ES에 동기적으로 저장합니다.
     * 재시도 로직 포함 (기본 3회, 지수 백오프)
     */
    suspend fun indexBatchSync(events: List<UserActionEvent>): Int {
        if (events.isEmpty()) return 0

        val startTime = System.nanoTime()
        var lastException: Exception? = null

        try {
            repeat(properties.maxRetries) { attempt ->
                try {
                    val bulkRequest = buildBulkRequest(events)
                    val response = esClient.bulk(bulkRequest)
                    return handleBulkResponse(response, events)
                } catch (e: Exception) {
                    lastException = e
                    retryCounter.increment()

                    if (attempt < properties.maxRetries - 1) {
                        // 지수 백오프: 1초, 2초, 4초, ...
                        val delayMs = properties.retryDelayMs * (1L shl attempt)
                        delay(delayMs)
                    }
                }
            }

            // 모든 재시도 실패 → DLQ 전송
            sendToDlq(events)
            return 0
        } finally {
            bulkIndexTimer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS)
        }
    }

    private fun buildBulkRequest(events: List<UserActionEvent>): BulkRequest {
        val bulkRequest = BulkRequest.Builder()

        events.forEach { event ->
            bulkRequest.operations { op ->
                op.index { idx ->
                    idx.index(indexName)
                        .id(event.traceId.toString())  // Idempotency 보장
                        .document(mapOf(
                            "traceId" to event.traceId.toString(),
                            "userId" to event.userId.toString(),
                            "productId" to event.productId.toString(),
                            "category" to event.category.toString(),
                            "actionType" to event.actionType.toString(),
                            "metadata" to event.metadata,
                            "timestamp" to event.timestamp.toEpochMilli()
                        ))
                }
            }
        }

        return bulkRequest.build()
    }

    private fun handleBulkResponse(response: BulkResponse, events: List<UserActionEvent>): Int {
        var successCount = 0

        if (response.errors()) {
            response.items().forEachIndexed { index, item ->
                if (item.error() != null) {
                    bulkFailedCounter.increment()
                    if (index < events.size) {
                        dlqProducer.sendSync(events[index])
                    }
                } else {
                    bulkSuccessCounter.increment()
                    successCount++
                }
            }
        } else {
            successCount = events.size
            bulkSuccessCounter.increment(events.size.toDouble())
        }

        return successCount
    }
}
```

### 3.3 Idempotent Consumer (중복 방지)

ES의 Document ID를 `traceId`로 설정하면 동일 메시지 재처리 시 자동으로 Upsert됩니다.

```kotlin
// Document ID = traceId (UUID)
idx.id(event.traceId)
```

### 3.4 Consumer 설정

```kotlin
@Configuration
class KafkaConsumerConfig {

    @Bean
    fun consumerFactory(): ConsumerFactory<String, UserActionEvent> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            ConsumerConfig.GROUP_ID_CONFIG to "behavior-consumer-group",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to KafkaAvroDeserializer::class.java,

            // 성능 튜닝
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 500,     // 한 번에 가져올 최대 레코드 수
            ConsumerConfig.FETCH_MIN_BYTES_CONFIG to 1024,     // 최소 fetch 크기
            ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG to 500,    // 최대 대기 시간

            // 안정성
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false, // 수동 커밋
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",

            // Avro
            "schema.registry.url" to "http://localhost:8081",
            "specific.avro.reader" to true
        )
        return DefaultKafkaConsumerFactory(props)
    }

    @Bean
    fun kafkaListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, UserActionEvent> {
        return ConcurrentKafkaListenerContainerFactory<String, UserActionEvent>().apply {
            consumerFactory = consumerFactory()
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            isBatchListener = true  // 배치 처리 활성화
            containerProperties.idleBetweenPolls = 100  // 폴링 간격
        }
    }
}
```

#### Consumer 설정 결정 근거

| 설정 | 값 | 근거 |
|-----|-----|------|
| `MAX_POLL_RECORDS` | 500 | Bulk 크기와 일치시켜 한 번의 poll → 한 번의 bulk 가능 |
| `ENABLE_AUTO_COMMIT` | false | 처리 완료 후 수동 커밋으로 메시지 유실 방지 |
| `AckMode` | MANUAL_IMMEDIATE | 배치 처리 완료 즉시 커밋 |


### 3.5 DLQ (Dead Letter Queue) 처리

```kotlin
@Component
class DlqProducer(
    private val kafkaTemplate: KafkaTemplate<String, UserActionEvent>
) {
    private val dlqTopic = "user.action.v1.dlq"

    fun send(event: UserActionEvent) {
        kafkaTemplate.send(dlqTopic, event.userId, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("Failed to send to DLQ", ex)
                    // 최후의 수단: 로컬 파일에 기록
                    FileUtils.appendToFile("failed_events.log", event.toString())
                }
            }
    }
}
```

### 3.6 DLQ 재처리 전략

| 방법 | 설명 | 적용 시점 |
|-----|------|----------|
| 자동 재시도 | DLQ Consumer가 주기적으로 재처리 시도 | 일시적 오류 |
| 수동 재처리 | 관리자가 원인 분석 후 CLI로 재처리 | 데이터 오류 |
| 폐기 | 일정 기간 경과 후 삭제 | 복구 불가능 |

```bash
# DLQ 메시지 수동 재처리 CLI
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic user.action.v1.dlq \
  --from-beginning | kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic user.action.v1
```

### 3.7 Consumer Concurrency 권장값

#### 파티션과 Concurrency 관계

| 파티션 수 | 권장 Concurrency | 설명 |
|-----------|------------------|------|
| 12 | 3-6 | 개발/스테이징 환경. Consumer 인스턴스당 2-4 파티션 할당 |
| 12 | 12 | 프로덕션 최대 병렬성. Consumer 인스턴스당 1 파티션 |

#### 설정 가이드

```yaml
# application.yml
consumer:
  concurrency: 3  # ConcurrentKafkaListenerContainerFactory 설정

spring:
  kafka:
    consumer:
      max-poll-records: 500  # 파티션당 한 번에 가져올 레코드 수
```

#### 권장 사항

1. **Concurrency ≤ 파티션 수**: Concurrency가 파티션 수를 초과하면 유휴 스레드 발생
2. **단일 인스턴스 권장**: 개발 환경에서는 `concurrency: 3`으로 충분
3. **스케일 아웃**: 프로덕션에서는 인스턴스 수 × concurrency ≤ 파티션 수 유지
4. **Virtual Threads 활용**: ES I/O 대기 시 Virtual Threads가 효율적으로 처리


## 4. Kafka Listener 전체 구현

> **중요:** ES 저장이 완료된 후에만 오프셋을 커밋하여 메시지 유실을 방지합니다.

```kotlin
@Component
class BehaviorEventListener(
    private val bulkIndexer: BulkIndexer,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val virtualThreadDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

    private val processedCounter = Counter.builder("kafka.consumer.processed")
        .tag("topic", "user.action.v1")
        .register(meterRegistry)

    private val indexedCounter = Counter.builder("kafka.consumer.indexed")
        .tag("topic", "user.action.v1")
        .register(meterRegistry)

    @KafkaListener(
        topics = ["\${consumer.topic}"],
        groupId = "behavior-consumer-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun consume(
        records: List<ConsumerRecord<String, UserActionEvent>>,
        acknowledgment: Acknowledgment
    ) {
        if (records.isEmpty()) {
            acknowledgment.acknowledge()
            return
        }

        // runBlocking으로 코루틴 완료까지 대기 (Virtual Thread에서 실행되므로 블로킹 안전)
        runBlocking(virtualThreadDispatcher) {
            try {
                // 1. 이벤트 추출
                val events = records.map { it.value() }

                // 2. ES에 동기적으로 저장 (저장 완료까지 대기)
                val indexedCount = bulkIndexer.indexBatchSync(events)

                // 3. 메트릭 업데이트
                processedCounter.increment(records.size.toDouble())
                indexedCounter.increment(indexedCount.toDouble())

                // 4. ES 저장 성공 후에만 오프셋 커밋
                acknowledgment.acknowledge()

            } catch (e: Exception) {
                log.error(e) { "Failed to process batch of ${records.size} records" }
                // 실패 시 커밋하지 않음 → Consumer 재시작 시 재처리됨
            }
        }
    }

    @PreDestroy
    fun cleanup() {
        virtualThreadDispatcher.close()
    }
}
```

> **Note:** 유저 취향 벡터 업데이트(`PreferenceUpdater`)는 ES 인덱싱 직후 best-effort로 처리됩니다.


## 5. 유저 취향 벡터 업데이트 (구현 완료)

ES 인덱싱 완료 후 유저 취향 벡터를 갱신합니다. 취향 벡터 갱신은 **best-effort**로 처리되어 실패해도 오프셋 커밋이 진행됩니다.

### 구현된 컴포넌트

| 컴포넌트 | 역할 |
|---------|------|
| `PreferenceUpdater` | 이벤트 배치 처리, 상품 벡터 조회 및 취향 벡터 갱신 조율 |
| `PreferenceVectorCalculator` | EMA 기반 취향 벡터 계산 |
| `ProductVectorRepository` | ES product_index에서 상품 벡터 조회 |
| `UserPreferenceRepository` | Redis (Primary) + ES (Backup) 하이브리드 저장소 |

### EMA 가중치 (행동별 차등 적용)

```kotlin
const val ALPHA_VIEW = 0.1f         // 조회: 약한 신호
const val ALPHA_WISHLIST = 0.1f     // 위시리스트: 약한 신호
const val ALPHA_SEARCH = 0.2f       // 검색: 중간 신호
const val ALPHA_CLICK = 0.3f        // 클릭: 중간 강도 신호
const val ALPHA_ADD_TO_CART = 0.3f  // 장바구니: 중간 강도 신호
const val ALPHA_PURCHASE = 0.5f     // 구매: 강한 신호
```

### 처리 흐름

1. Kafka 배치 수신 후 ES Bulk 인덱싱
2. 인덱싱 성공 시 `PreferenceUpdater.updatePreferencesBatch()` 호출
3. 유저별로 이벤트 그룹화 후 상품 벡터 일괄 조회
4. EMA로 취향 벡터 갱신
5. Redis 저장 + ES 백업

> **Note:** 상품 벡터가 없는 경우 해당 이벤트는 skip됩니다. 상품 벡터는 Phase 3의 Embedding Service에서 생성됩니다.


## 6. Phase 2 성공 기준 (Exit Criteria)

| 기준 | 측정 방법 | 목표 |
|-----|----------|------|
| 데이터 정합성 | Kafka lag == 0 && ES 문서 수 일치 | 100% |
| 처리량 | Prometheus 메트릭 | 초당 500건 이상 |
| 지연 시간 | ES refresh 후 검색 가능 | 5초 이내 |
| 중복 방지 | 동일 traceId 재전송 테스트 | 문서 수 증가 없음 |
| 장애 복구 | Consumer 재시작 테스트 | 데이터 유실 없음 |


## 7. 모니터링 대시보드 (Grafana)

### 핵심 메트릭

```promql
# Consumer Lag
kafka_consumer_lag{group="behavior-consumer-group"}

# 처리량 (초당)
rate(kafka_consumer_processed_total[1m])

# ES Bulk 성공률
rate(es_bulk_success_total[1m]) / rate(es_bulk_total[1m])

# DLQ 발생량
rate(kafka_producer_record_send_total{topic="user.action.v1.dlq"}[5m])
```


## 8. 관련 문서

- [Phase 1: 트래픽 시뮬레이터](./phase%201.md)
- [Phase 3: 실시간 추천 엔진](./phase%203.md)
- [ADR-002: Schema Registry](./adr-002-schema-registry.md)
- [Infrastructure: 인프라 구성](./infrastructure.md)