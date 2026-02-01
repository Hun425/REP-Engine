# Phase 4: 실시간 알림 시스템 (Notification System)

본 문서는 상품 가격 변동, 재고 입고 등 비즈니스 이벤트 발생 시 관심 유저에게 실시간으로 알림을 발송하는 시스템의 상세 설계를 다룹니다.

## 1. 시스템 개요

### 1.1 목표

- 상품 가격이 하락하면 해당 상품에 관심을 보인 유저에게 알림 발송
- 품절 상품이 재입고되면 장바구니에 담은 유저에게 알림 발송
- 매일 활성 유저에게 개인화 추천 상품 알림 발송 (ShedLock + @Scheduled)
- 알림 발송 지연 시간 30초 이내

### 1.2 지원 알림 채널

| 채널 | 구현 | 비고 |
|-----|------|------|
| Push Notification | 시뮬레이션 (로그 출력) | FCM/APNs 연동 가능 |
| SMS | 시뮬레이션 | 외부 SMS Gateway 연동 가능 |
| Email | 시뮬레이션 | SendGrid/SES 연동 가능 |
| In-App | Kafka → WebSocket | 실시간 앱 내 알림 |


## 2. 아키텍처 (Architecture)

### 2.1 전체 흐름

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Notification System Architecture                         │
└─────────────────────────────────────────────────────────────────────────────────┘

  [상품 시스템]                                            [유저 디바이스]
       │                                                        ▲
       ▼                                                        │
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ 1. Inventory │    │ 2. Event     │    │ 3. Target    │    │ 4. Push      │
│    Event     │───▶│    Detector  │───▶│    Resolver  │───▶│    Sender    │
│              │    │              │    │              │    │              │
└──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
       │                   │                   │                   │
       ▼                   ▼                   ▼                   ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│product.      │    │가격 하락     │    │ES 쿼리로    │    │FCM/APNs     │
│inventory.v1  │    │재고 입고     │    │관심 유저 추출│   │시뮬레이션    │
└──────────────┘    │감지          │    └──────────────┘    └──────────────┘
                    └──────────────┘
                           │
                           ▼
                    ┌──────────────┐
                    │notification. │
                    │push.v1       │
                    └──────────────┘
```

### 2.2 컴포넌트 상세

| 컴포넌트 | 역할 | 입력 | 출력 |
|---------|------|------|------|
| **Inventory Consumer** | 재고/가격 변동 이벤트 소비 | `product.inventory.v1` | 내부 처리 |
| **Event Detector** | 알림 발송 조건 감지 (가격↓, 재고↑) | 이벤트 | 알림 트리거 |
| **Target Resolver** | 알림 대상 유저 추출 (ES 쿼리) | 상품 ID | 유저 ID 목록 |
| **Notification Producer** | 알림 메시지 생성 및 Kafka 발행 | 유저 + 상품 | `notification.push.v1` |
| **Push Sender** | 실제 알림 발송 (시뮬레이션) | 알림 메시지 | 발송 결과 |
| **Recommendation Scheduler** | 일일 추천 알림 배치 (ShedLock) | Cron 스케줄 | `notification.push.v1` |


## 3. 이벤트 스키마

### 3.1 ProductInventoryEvent (Avro)

```json
{
  "type": "record",
  "name": "ProductInventoryEvent",
  "namespace": "com.rep.event.product",
  "fields": [
    {"name": "eventId", "type": "string"},
    {"name": "productId", "type": "string"},
    {"name": "eventType", "type": {
      "type": "enum",
      "name": "InventoryEventType",
      "symbols": ["PRICE_CHANGE", "STOCK_CHANGE", "PRODUCT_UPDATE"]
    }},
    {"name": "previousPrice", "type": ["null", "float"], "default": null},
    {"name": "currentPrice", "type": ["null", "float"], "default": null},
    {"name": "previousStock", "type": ["null", "int"], "default": null},
    {"name": "currentStock", "type": ["null", "int"], "default": null},
    {"name": "timestamp", "type": "long", "logicalType": "timestamp-millis"}
  ]
}
```

### 3.2 NotificationEvent (Avro)

```json
{
  "type": "record",
  "name": "NotificationEvent",
  "namespace": "com.rep.event.notification",
  "fields": [
    {"name": "notificationId", "type": "string"},
    {"name": "userId", "type": "string"},
    {"name": "productId", "type": "string"},
    {"name": "notificationType", "type": {
      "type": "enum",
      "name": "NotificationType",
      "symbols": ["PRICE_DROP", "BACK_IN_STOCK", "RECOMMENDATION"]
    }},
    {"name": "title", "type": "string"},
    {"name": "body", "type": "string"},
    {"name": "data", "type": {"type": "map", "values": "string"}},
    {"name": "channels", "type": {"type": "array", "items": {
      "type": "enum",
      "name": "Channel",
      "symbols": ["PUSH", "SMS", "EMAIL", "IN_APP"]
    }}},
    {"name": "priority", "type": {
      "type": "enum",
      "name": "Priority",
      "symbols": ["LOW", "NORMAL", "HIGH"]
    }},
    {"name": "timestamp", "type": "long"}
  ]
}
```


## 4. 핵심 구현

### 4.1 Inventory Event Consumer

> **Note**: 실제 구현에서는 `Acknowledgment` 파라미터를 사용하지 않습니다.
> `KafkaConsumerConfig`에 설정된 `DefaultErrorHandler`가 자동으로 재시도/DLQ 처리를 담당합니다.

```kotlin
@Component
class InventoryEventConsumer(
    private val eventDetector: EventDetector,
    @Qualifier("virtualThreadDispatcher")
    private val dispatcher: CloseableCoroutineDispatcher,
    meterRegistry: MeterRegistry
) {
    private val processedCounter = Counter.builder("inventory.events.processed")
        .register(meterRegistry)
    private val errorCounter = Counter.builder("inventory.events.error")
        .register(meterRegistry)

    @KafkaListener(
        topics = ["\${notification.inventory-topic}"],
        groupId = "notification-consumer-group",
        containerFactory = "inventoryListenerContainerFactory"
    )
    fun consume(record: ConsumerRecord<String, ProductInventoryEvent>) {
        val event = record.value()

        try {
            runBlocking(dispatcher) {
                when (event.eventType) {
                    InventoryEventType.PRICE_CHANGE -> {
                        eventDetector.detectPriceDrop(event)
                    }
                    InventoryEventType.STOCK_CHANGE -> {
                        eventDetector.detectRestock(event)
                    }
                    else -> {
                        // 다른 이벤트는 무시
                    }
                }
            }
            processedCounter.increment()
            // 정상 완료: Spring Kafka가 자동으로 offset 커밋
        } catch (e: Exception) {
            errorCounter.increment()
            log.error(e) { "Failed to process inventory event: ${event.eventId}" }
            // 예외 전파 → DefaultErrorHandler가 3회 재시도 후 DLQ 전송
            throw e
        }
    }
}
```

**에러 처리 흐름**:
1. 정상 처리: Spring Kafka가 자동으로 offset 커밋
2. 처리 실패: `DefaultErrorHandler`가 `FixedBackOff(1000ms, 3)` 정책으로 재시도
3. 재시도 소진: `DeadLetterPublishingRecoverer`가 `product.inventory.v1.dlq` 토픽으로 전송

### 4.2 Event Detector (알림 조건 감지)

```kotlin
@Component
class EventDetector(
    private val targetResolver: TargetResolver,
    private val notificationProducer: NotificationProducer,
    private val rateLimiter: NotificationRateLimiter,
    private val productRepository: ProductRepository,
    private val properties: NotificationProperties,
    meterRegistry: MeterRegistry
) {
    private val priceDropDetectedCounter = Counter.builder("notification.event.detected")
        .tag("type", "price_drop")
        .register(meterRegistry)

    private val notificationTriggeredCounter = Counter.builder("notification.triggered")
        .register(meterRegistry)

    private val rateLimitedCounter = Counter.builder("notification.rate.limited")
        .register(meterRegistry)

    /**
     * 가격 하락 감지
     * 조건: 가격이 설정된 임계값(기본 10%) 이상 하락한 경우
     */
    suspend fun detectPriceDrop(event: ProductInventoryEvent) {
        val previousPrice = event.previousPrice ?: return
        val currentPrice = event.currentPrice ?: return
        if (previousPrice <= 0) return

        val dropPercentage = ((previousPrice - currentPrice) / previousPrice * 100).toInt()

        if (dropPercentage >= properties.priceDropThreshold) {
            priceDropDetectedCounter.increment()
            log.info { "Price drop detected: productId=${event.productId}, drop=$dropPercentage%" }

            // 해당 상품에 관심 보인 유저 조회
            val targetUsers = targetResolver.findInterestedUsers(
                productId = event.productId.toString(),
                actionTypes = listOf("VIEW", "CLICK", "ADD_TO_CART"),
                withinDays = properties.interestedUserDays
            )

            if (targetUsers.isEmpty()) return

            // 상품 정보 조회
            val product = productRepository.findById(event.productId.toString())
            val productName = product?.productName ?: "상품"

            // 알림 발송 (배치 처리로 Kafka 버스트 방지)
            var sentCount = 0
            val batches = targetUsers.chunked(properties.batchSize)

            for ((batchIndex, batch) in batches.withIndex()) {
                for (userId in batch) {
                    // Rate Limit 체크 (canSend = shouldSend + checkDailyLimit)
                    if (!rateLimiter.canSend(userId, event.productId.toString(), "PRICE_DROP")) {
                        rateLimitedCounter.increment()
                        continue
                    }

                    val notification = NotificationEvent.newBuilder()
                        .setNotificationId(UUID.randomUUID().toString())
                        .setUserId(userId)
                        .setProductId(event.productId.toString())
                        .setNotificationType(NotificationType.PRICE_DROP)
                        .setTitle("가격이 떨어졌어요!")
                        .setBody("${productName}이(가) ${dropPercentage}% 할인 중입니다!")
                        .setData(mapOf(
                            "previousPrice" to previousPrice.toString(),
                            "currentPrice" to currentPrice.toString(),
                            "dropPercentage" to dropPercentage.toString()
                        ))
                        .setChannels(listOf(Channel.PUSH, Channel.IN_APP))
                        .setPriority(Priority.HIGH)
                        .setTimestamp(Instant.now())
                        .build()

                    notificationProducer.send(notification)
                    sentCount++
                    notificationTriggeredCounter.increment()
                }

                // 마지막 배치가 아니면 딜레이 적용 (Kafka 버스트 방지)
                if (batchIndex < batches.size - 1) {
                    delay(properties.batchDelayMs)
                }
            }

            log.info { "Price drop notifications sent: sent=$sentCount, batches=${batches.size}" }
        }
    }

    /**
     * 재입고 감지
     * 조건: 재고가 0 → 1 이상으로 변경된 경우
     */
    suspend fun detectRestock(event: ProductInventoryEvent) {
        val previousStock = event.previousStock ?: return
        val currentStock = event.currentStock ?: return

        if (previousStock == 0 && currentStock > 0) {
            log.info { "Restock detected: productId=${event.productId}" }

            // 장바구니에 담은 유저 조회
            val targetUsers = targetResolver.findUsersWithCartItem(event.productId.toString())

            if (targetUsers.isEmpty()) return

            val product = productRepository.findById(event.productId.toString())
            val productName = product?.productName ?: "상품"

            // 알림 발송 (배치 처리로 Kafka 버스트 방지)
            var sentCount = 0
            val batches = targetUsers.chunked(properties.batchSize)

            for ((batchIndex, batch) in batches.withIndex()) {
                for (userId in batch) {
                    // Rate Limit 체크
                    if (!rateLimiter.canSend(userId, event.productId.toString(), "BACK_IN_STOCK")) {
                        rateLimitedCounter.increment()
                        continue
                    }

                    val notification = NotificationEvent.newBuilder()
                        .setNotificationId(UUID.randomUUID().toString())
                        .setUserId(userId)
                        .setProductId(event.productId.toString())
                        .setNotificationType(NotificationType.BACK_IN_STOCK)
                        .setTitle("재입고 알림")
                        .setBody("${productName}이(가) 다시 입고되었습니다!")
                        .setData(mapOf("currentStock" to currentStock.toString()))
                        .setChannels(listOf(Channel.PUSH, Channel.SMS))
                        .setPriority(Priority.HIGH)
                        .setTimestamp(Instant.now())
                        .build()

                    notificationProducer.send(notification)
                    sentCount++
                    notificationTriggeredCounter.increment()
                }

                // 마지막 배치가 아니면 딜레이 적용 (Kafka 버스트 방지)
                if (batchIndex < batches.size - 1) {
                    delay(properties.batchDelayMs)
                }
            }

            log.info { "Restock notifications sent: sent=$sentCount, batches=${batches.size}" }
        }
    }
}
```

### 4.3 Target Resolver (대상 유저 추출)

```kotlin
@Component
class TargetResolver(
    private val esClient: ElasticsearchClient,
    private val properties: NotificationProperties,
    meterRegistry: MeterRegistry
) {
    /**
     * 특정 상품에 관심을 보인 유저 추출
     *
     * @param productId 상품 ID
     * @param actionTypes 관심 행동 유형 (문자열 리스트: "VIEW", "CLICK", "ADD_TO_CART" 등)
     * @param withinDays 조회 기간 (일)
     * @return 유저 ID 목록
     */
    fun findInterestedUsers(
        productId: String,
        actionTypes: List<String>,
        withinDays: Int = properties.interestedUserDays
    ): List<String> {
        val response = esClient.search({ s ->
            s.index("user_behavior_index")
                .size(0)  // 집계만 필요
                .query { q ->
                    q.bool { b ->
                        b.must { m ->
                            m.term { t -> t.field("productId").value(productId) }
                        }
                        b.must { m ->
                            m.terms { t ->
                                t.field("actionType").terms { tv ->
                                    tv.value(actionTypes.map { FieldValue.of(it) })
                                }
                            }
                        }
                        b.must { m ->
                            m.range { r ->
                                r.field("timestamp").gte(JsonData.of("now-${withinDays}d"))
                            }
                        }
                        b
                    }
                }
                .aggregations("users") { agg ->
                    agg.terms { t ->
                        t.field("userId").size(properties.targetUserLimit)
                    }
                }
        }, Void::class.java)

        return response.aggregations()["users"]
            ?.sterms()?.buckets()?.array()
            ?.map { it.key().stringValue() } ?: emptyList()
    }

    /**
     * 장바구니에 특정 상품을 담은 유저 추출
     * 재입고 알림 대상 유저 조회에 사용됩니다.
     */
    fun findUsersWithCartItem(productId: String): List<String> {
        return findInterestedUsers(
            productId = productId,
            actionTypes = listOf("ADD_TO_CART"),
            withinDays = properties.cartUserDays
        )
    }
}
```

### 4.4 Notification Producer

```kotlin
@Component
class NotificationProducer(
    private val kafkaTemplate: KafkaTemplate<String, NotificationEvent>
) {
    private val topic = "notification.push.v1"

    fun send(notification: NotificationEvent) {
        kafkaTemplate.send(topic, notification.userId, notification)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error("Failed to send notification: ${notification.notificationId}", ex)
                    Metrics.counter("notifications.send.failed").increment()
                } else {
                    log.debug("Notification sent: ${notification.notificationId}")
                    Metrics.counter("notifications.send.success").increment()
                }
            }
    }
}
```

### 4.5 Push Sender (시뮬레이션)

```kotlin
@Component
class PushSenderSimulator(
    private val meterRegistry: MeterRegistry
) {
    private val sentCounter = Counter.builder("notifications.sent")
        .register(meterRegistry)

    @KafkaListener(
        topics = ["notification.push.v1"],
        groupId = "push-sender-group",
        concurrency = "6"  // 6개 파티션에 맞춤
    )
    fun consume(
        record: ConsumerRecord<String, NotificationEvent>,
        acknowledgment: Acknowledgment
    ) {
        val notification = record.value()

        // 각 채널별로 발송 시뮬레이션
        notification.channels.forEach { channel ->
            sendToChannel(channel, notification)
        }

        sentCounter.increment()
        acknowledgment.acknowledge()
    }

    private fun sendToChannel(channel: Channel, notification: NotificationEvent) {
        // 실제 구현에서는 각 채널별 클라이언트 호출
        when (channel) {
            Channel.PUSH -> simulatePush(notification)
            Channel.SMS -> simulateSms(notification)
            Channel.EMAIL -> simulateEmail(notification)
            Channel.IN_APP -> simulateInApp(notification)
        }
    }

    private fun simulatePush(notification: NotificationEvent) {
        // FCM/APNs 호출 시뮬레이션
        log.info("""
            [PUSH] Sending to ${notification.userId}
            Title: ${notification.title}
            Body: ${notification.body}
            Data: ${notification.data}
        """.trimIndent())

        // 실제 구현 예시:
        // fcmClient.send(Message.builder()
        //     .setToken(userDeviceToken)
        //     .setNotification(Notification.builder()
        //         .setTitle(notification.title)
        //         .setBody(notification.body)
        //         .build())
        //     .putAllData(notification.data)
        //     .build())
    }

    private fun simulateSms(notification: NotificationEvent) {
        log.info("[SMS] ${notification.userId}: ${notification.body}")
    }

    private fun simulateEmail(notification: NotificationEvent) {
        log.info("[EMAIL] ${notification.userId}: ${notification.title} - ${notification.body}")
    }

    private fun simulateInApp(notification: NotificationEvent) {
        log.info("[IN_APP] ${notification.userId}: ${notification.title}")
        // WebSocket을 통해 실시간 전송 가능
    }
}
```

### 4.6 Recommendation Scheduler (일일 추천 알림 배치)

매일 09:00에 활성 유저에게 개인화 추천 상품 알림을 발송합니다.

**ShedLock을 사용하는 이유:**
- 다중 인스턴스 환경에서 스케줄러가 중복 실행되는 것을 방지
- Redis 기반 분산 락으로 단일 인스턴스에서만 배치 실행 보장
- Spring Batch/Quartz 대비 낮은 복잡도 (추가 DB 불필요)

```
┌────────────────────────────────────────────────────────────────┐
│              RECOMMENDATION 알림 흐름 (ShedLock)                 │
└────────────────────────────────────────────────────────────────┘

[매일 09:00 스케줄러]
        │
        ▼
┌──────────────────┐
│ Redis 분산 락    │ ← ShedLock: 단일 인스턴스만 실행 보장
│ (ShedLock)      │
└──────────────────┘
        │ 락 획득 성공
        ▼
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│ 1. 활성 유저 조회  │────▶│ 2. 추천 API 호출  │────▶│ 3. 알림 발송      │
│ (ES Aggregation) │     │ (HTTP Client)    │     │ (Kafka Producer) │
└──────────────────┘     └──────────────────┘     └──────────────────┘
        │                        │                        │
        ▼                        ▼                        ▼
   user_behavior_index    recommendation-api      notification.push.v1
```

```kotlin
@Component
@ConditionalOnProperty(
    prefix = "notification.recommendation",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationScheduler(
    private val activeUserRepository: ActiveUserRepository,
    private val recommendationClient: RecommendationClient,
    private val notificationProducer: NotificationProducer,
    private val rateLimiter: NotificationRateLimiter,
    private val properties: NotificationProperties,
    @param:Qualifier("virtualThreadDispatcher")
    private val dispatcher: CloseableCoroutineDispatcher,
    meterRegistry: MeterRegistry
) {
    /**
     * 매일 지정 시간에 활성 유저에게 추천 알림 발송
     *
     * @SchedulerLock 설정:
     * - lockAtMostFor: 최대 1시간 락 유지 (장애 시 자동 해제)
     * - lockAtLeastFor: 최소 5분 락 유지 (빠른 완료 시에도 재실행 방지)
     */
    @Scheduled(cron = "\${notification.recommendation.cron:0 0 9 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(
        name = "dailyRecommendationBatch",
        lockAtMostFor = "1h",
        lockAtLeastFor = "5m"
    )
    fun sendDailyRecommendations() {
        val sample = Timer.start()
        log.info { "Starting daily recommendation batch job" }

        runBlocking(dispatcher) {
            try {
                executeBatch()
            } catch (e: Exception) {
                log.error(e) { "Daily recommendation batch failed" }
            }
        }
    }

    private suspend fun executeBatch() {
        // 1. 활성 유저 조회 (ES terms aggregation)
        val activeUsers = activeUserRepository.getActiveUsers(
            properties.recommendation.activeUserDays
        )

        if (activeUsers.isEmpty()) {
            log.info { "No active users found, skipping batch" }
            return
        }

        log.info { "Found ${activeUsers.size} active users for recommendation" }

        // 2. 배치 처리 (Kafka 버스트 방지)
        var sentCount = 0
        val batches = activeUsers.chunked(properties.batchSize)

        for ((batchIndex, batch) in batches.withIndex()) {
            for (userId in batch) {
                // Rate limit 체크
                if (!rateLimiter.canSend(userId, "DAILY_RECOMMENDATION", "RECOMMENDATION")) {
                    continue
                }

                // 추천 상품 조회 (recommendation-api HTTP 호출)
                val recommendations = recommendationClient.getRecommendations(
                    userId = userId,
                    limit = properties.recommendation.limit
                )

                if (recommendations == null || recommendations.recommendations.isEmpty()) {
                    continue
                }

                // 알림 메시지 생성 및 발송
                val notification = createNotification(userId, recommendations)
                notificationProducer.send(notification)
                sentCount++
            }

            // 마지막 배치가 아니면 딜레이 적용
            if (batchIndex < batches.size - 1) {
                delay(properties.batchDelayMs)
            }
        }

        log.info { "Daily recommendation batch completed: sent=$sentCount" }
    }

    private fun createNotification(
        userId: String,
        recommendations: RecommendationResponse
    ): NotificationEvent {
        val products = recommendations.recommendations
        val productNames = products.joinToString(", ") { it.productName }

        return NotificationEvent.newBuilder()
            .setNotificationId(UUID.randomUUID().toString())
            .setUserId(userId)
            .setProductId(products.firstOrNull()?.productId ?: "unknown")
            .setNotificationType(NotificationType.RECOMMENDATION)
            .setTitle("오늘의 추천 상품")
            .setBody("${productNames}을(를) 추천드려요!")
            .setData(mapOf(
                "strategy" to recommendations.strategy,
                "productIds" to products.joinToString(",") { it.productId }
            ))
            .setChannels(listOf(Channel.PUSH, Channel.IN_APP))
            .setPriority(Priority.NORMAL)
            .setTimestamp(Instant.now())
            .build()
    }
}
```

**설정 예시 (application.yml):**

```yaml
notification:
  # 배치 처리 설정
  batch-size: ${NOTIFICATION_BATCH_SIZE:100}
  batch-delay-ms: ${NOTIFICATION_BATCH_DELAY_MS:50}

  # 추천 알림 배치 설정
  recommendation:
    enabled: ${RECOMMENDATION_ENABLED:true}
    cron: ${RECOMMENDATION_CRON:0 0 9 * * *}
    active-user-days: ${RECOMMENDATION_ACTIVE_USER_DAYS:7}
    limit: ${RECOMMENDATION_LIMIT:3}
    api-url: ${RECOMMENDATION_API_URL:http://recommendation-api:8082}
```

**장애 대응:**

| 상황 | 대응 |
|------|------|
| 09:00에 서비스 다운 | 락 미획득, 복구 후 수동 트리거 API 호출 |
| 배치 중간 장애 | lockAtMostFor=1h 후 자동 해제, 다음 날 재실행 |
| Redis 장애 | 락 획득 불가 → 배치 실행 안됨 (fail-safe) |


## 5. 알림 중복 방지

### 5.1 유저별 알림 빈도 제한

```kotlin
@Component
class NotificationRateLimiter(
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) {
    /**
     * 동일 유저 + 동일 상품에 대해 중복 알림 방지
     *
     * SETNX (setIfAbsent) 사용으로 원자적 연산을 보장합니다.
     * - 키가 없으면: 키 설정 + true 반환 (발송 허용)
     * - 키가 있으면: false 반환 (중복 차단)
     *
     * fail-close 정책: Redis 오류 시 발송 차단 (안전 우선)
     */
    suspend fun shouldSend(userId: String, productId: String, notificationType: String): Boolean {
        val key = "$SENT_KEY_PREFIX$userId:$productId:$notificationType"

        return try {
            // 원자적 SETNX: 키가 없으면 설정하고 true, 있으면 false
            val wasSet = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofHours(properties.duplicatePreventionHours))
                .awaitSingleOrNull() ?: false

            if (!wasSet) {
                log.debug { "Duplicate notification blocked: userId=$userId, productId=$productId" }
                duplicateBlockedCounter.increment()
            }

            wasSet
        } catch (e: Exception) {
            log.error(e) { "Failed to check duplicate (blocking - fail-close policy)" }
            failCloseBlockedCounter.increment()
            // Redis 오류 시 발송 차단 (fail-close)
            false
        }
    }

    /**
     * 유저별 일일 알림 횟수 제한
     *
     * TTL Race 방지: setIfAbsent로 TTL과 함께 키 생성을 보장한 후 increment.
     * 기존 방식(increment 후 expire)은 두 연산 사이 장애 시 TTL 없는 키가 남을 수 있음.
     *
     * fail-close 정책: Redis 오류 시 발송 차단 (안전 우선)
     */
    suspend fun checkDailyLimit(userId: String): Boolean {
        val key = "$DAILY_KEY_PREFIX$userId"

        return try {
            val ttl = calculateTtlUntilMidnight()

            // 1. 키가 없으면 TTL과 함께 생성 (원자적)
            //    키가 이미 있으면 무시됨 (기존 TTL 유지)
            redisTemplate.opsForValue()
                .setIfAbsent(key, "0", ttl)
                .awaitSingleOrNull()

            // 2. 카운트 증가 (항상 TTL이 설정된 상태에서 실행됨)
            val count = redisTemplate.opsForValue()
                .increment(key)
                .awaitSingleOrNull() ?: 1L

            if (count > properties.dailyLimitPerUser) {
                log.debug { "Daily limit exceeded for userId=$userId, count=$count" }
                dailyLimitBlockedCounter.increment()
                return false
            }

            true
        } catch (e: Exception) {
            log.error(e) { "Failed to check daily limit (blocking - fail-close policy)" }
            failCloseBlockedCounter.increment()
            // Redis 오류 시 발송 차단 (fail-close)
            false
        }
    }

    /**
     * 중복 체크와 일일 제한을 모두 확인합니다.
     */
    suspend fun canSend(userId: String, productId: String, notificationType: String): Boolean {
        return shouldSend(userId, productId, notificationType) && checkDailyLimit(userId)
    }

    private fun calculateTtlUntilMidnight(): Duration {
        val now = LocalDateTime.now()
        val midnight = now.toLocalDate().plusDays(1).atTime(LocalTime.MIDNIGHT)
        return Duration.between(now, midnight)
    }
}
```

### 5.2 Event Detector에 Rate Limiter 적용

> **Note:** Rate Limiter는 위 4.2절의 `EventDetector` 코드에서 `rateLimiter.canSend()` 호출로 이미 통합되어 있습니다.
>
> `canSend()`는 `shouldSend()` (중복 방지) + `checkDailyLimit()` (일일 제한)을 모두 확인합니다.


## 6. 알림 이력 저장

```kotlin
@Component
class NotificationHistoryService(
    private val esClient: ElasticsearchClient
) {
    fun save(notification: NotificationEvent, status: SendStatus) {
        esClient.index { i ->
            i.index("notification_history_index")
                .id(notification.notificationId)
                .document(NotificationHistory(
                    notificationId = notification.notificationId,
                    userId = notification.userId,
                    productId = notification.productId,
                    type = notification.notificationType.name,
                    title = notification.title,
                    channels = notification.channels.map { it.name },
                    status = status.name,
                    sentAt = Instant.now()
                ))
        }
    }
}

data class NotificationHistory(
    val notificationId: String,
    val userId: String,
    val productId: String,
    val type: String,
    val title: String,
    val channels: List<String>,
    val status: String,
    val sentAt: Instant
)

enum class SendStatus {
    SENT, FAILED, RATE_LIMITED, USER_OPTED_OUT
}
```


## 7. DLQ (Dead Letter Queue)

처리 실패한 메시지를 별도 토픽으로 이동하여 추후 분석 및 재처리합니다.

### 7.1 DLQ 흐름

```
┌──────────────────────────────────────────────────────────────────┐
│                    DLQ 처리 흐름                                  │
└──────────────────────────────────────────────────────────────────┘

[product.inventory.v1]
        │
        ▼
┌──────────────────┐
│ Consumer 처리    │ ← 처리 시도
└──────────────────┘
        │
        ▼ 실패
┌──────────────────┐
│ 재시도 (3회)     │ ← FixedBackOff: 1초 간격
└──────────────────┘
        │
        ▼ 모두 실패
┌──────────────────┐
│ DLQ 토픽으로 전송 │ → product.inventory.v1.dlq
└──────────────────┘
```

### 7.2 설정

```yaml
notification:
  dlq:
    enabled: true           # DLQ 활성화
    topic-suffix: .dlq      # DLQ 토픽 접미사
    max-retries: 3          # 재시도 횟수
    retry-backoff-ms: 1000  # 재시도 간격 (ms)
```

### 7.3 DLQ 구현 (Spring Kafka)

```kotlin
@Bean
fun kafkaErrorHandler(
    deadLetterPublishingRecoverer: DeadLetterPublishingRecoverer
): CommonErrorHandler {
    val backOff = FixedBackOff(
        properties.dlq.retryBackoffMs,
        properties.dlq.maxRetries.toLong()
    )

    return DefaultErrorHandler(deadLetterPublishingRecoverer, backOff).apply {
        setRetryListeners({ record, ex, deliveryAttempt ->
            log.warn { "Retry attempt $deliveryAttempt for record: topic=${record.topic()}" }
        })
    }
}

@Bean
fun deadLetterPublishingRecoverer(
    dlqKafkaTemplate: KafkaTemplate<String, Any>
): DeadLetterPublishingRecoverer {
    return DeadLetterPublishingRecoverer(dlqKafkaTemplate) { record, ex ->
        val dlqTopic = record.topic() + properties.dlq.topicSuffix
        TopicPartition(dlqTopic, record.partition())
    }
}
```

### 7.4 DLQ 메시지 재처리 (운영)

```bash
# DLQ 토픽 메시지 확인
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic product.inventory.v1.dlq \
  --from-beginning

# 재처리 시: DLQ → 원본 토픽으로 복사 (수동)
```


## 8. 성공 기준 (Exit Criteria)

| 기준 | 측정 방법 | 목표 |
|-----|----------|------|
| 알림 지연 시간 | 이벤트 발생 → 알림 전송 완료 | 30초 이내 |
| 처리량 | 동시 가격 변동 이벤트 처리 | 초당 100건 이상 |
| 대상 추출 정확도 | 관심 유저 쿼리 결과 검증 | 100% (ES 쿼리 기반) |
| 중복 방지 | 동일 알림 재발송 테스트 | 1시간 내 중복 없음 |
| 일일 제한 | 동일 유저 11회차 알림 시도 | 발송 차단됨 |


## 8. 확장 가능성

### 8.1 실제 Push 서비스 연동

```kotlin
// FCM 연동 예시
@Component
class FcmPushSender(
    private val firebaseMessaging: FirebaseMessaging,
    private val userDeviceRepository: UserDeviceRepository
) {
    suspend fun send(notification: NotificationEvent) {
        val deviceToken = userDeviceRepository.getToken(notification.userId)
            ?: return

        val message = Message.builder()
            .setToken(deviceToken)
            .setNotification(
                Notification.builder()
                    .setTitle(notification.title)
                    .setBody(notification.body)
                    .build()
            )
            .putAllData(notification.data)
            .build()

        firebaseMessaging.sendAsync(message).await()
    }
}
```

### 8.2 알림 구독 관리

```kotlin
// 유저별 알림 설정
data class UserNotificationPreference(
    val userId: String,
    val priceDropEnabled: Boolean = true,
    val restockEnabled: Boolean = true,
    val channels: List<Channel> = listOf(Channel.PUSH, Channel.IN_APP),
    val quietHoursStart: LocalTime? = null,  // 방해 금지 시작
    val quietHoursEnd: LocalTime? = null     // 방해 금지 종료
)
```


## 9. 관련 문서

- [Phase 3: 실시간 추천 엔진](./phase%203.md)
- [Phase 5: 운영 모니터링](./phase%205.md)
- [ADR-002: Schema Registry](./adr-002-schema-registry.md)
- [Infrastructure: 인프라 구성](./infrastructure.md)