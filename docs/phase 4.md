# Phase 4: 실시간 알림 시스템 (Notification System)

본 문서는 상품 가격 변동, 재고 입고 등 비즈니스 이벤트 발생 시 관심 유저에게 실시간으로 알림을 발송하는 시스템의 상세 설계를 다룹니다.

## 1. 시스템 개요

### 1.1 목표

- 상품 가격이 하락하면 해당 상품에 관심을 보인 유저에게 알림 발송
- 품절 상품이 재입고되면 장바구니에 담은 유저에게 알림 발송
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

```kotlin
@Component
class InventoryEventConsumer(
    private val eventDetector: EventDetector,
    private val meterRegistry: MeterRegistry
) {
    private val processedCounter = Counter.builder("inventory.events.processed")
        .register(meterRegistry)

    @KafkaListener(
        topics = ["product.inventory.v1"],
        groupId = "notification-consumer-group"
    )
    fun consume(
        record: ConsumerRecord<String, ProductInventoryEvent>,
        acknowledgment: Acknowledgment
    ) {
        val event = record.value()

        try {
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

            processedCounter.increment()
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            log.error("Failed to process inventory event: ${event.eventId}", e)
            // 재시도 또는 DLQ
        }
    }
}
```

### 4.2 Event Detector (알림 조건 감지)

```kotlin
@Component
class EventDetector(
    private val targetResolver: TargetResolver,
    private val notificationProducer: NotificationProducer,
    private val productRepository: ProductRepository
) {
    /**
     * 가격 하락 감지
     * 조건: 가격이 10% 이상 하락한 경우
     */
    suspend fun detectPriceDrop(event: ProductInventoryEvent) {
        val previousPrice = event.previousPrice ?: return
        val currentPrice = event.currentPrice ?: return

        val dropPercentage = (previousPrice - currentPrice) / previousPrice * 100

        if (dropPercentage >= 10) {
            log.info("Price drop detected: ${event.productId}, $dropPercentage%")

            // 해당 상품에 관심 보인 유저 조회
            val targetUsers = targetResolver.findInterestedUsers(
                productId = event.productId,
                actionTypes = listOf(ActionType.VIEW, ActionType.CLICK, ActionType.ADD_TO_CART),
                withinDays = 30
            )

            // 상품 정보 조회
            val product = productRepository.findById(event.productId) ?: return

            // 알림 발송
            targetUsers.forEach { userId ->
                notificationProducer.send(
                    NotificationEvent(
                        notificationId = UUID.randomUUID().toString(),
                        userId = userId,
                        productId = event.productId,
                        notificationType = NotificationType.PRICE_DROP,
                        title = "가격이 떨어졌어요!",
                        body = "${product.name}이(가) ${dropPercentage.toInt()}% 할인 중입니다!",
                        data = mapOf(
                            "previousPrice" to previousPrice.toString(),
                            "currentPrice" to currentPrice.toString(),
                            "dropPercentage" to dropPercentage.toString()
                        ),
                        channels = listOf(Channel.PUSH, Channel.IN_APP),
                        priority = Priority.HIGH,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            Metrics.counter("notifications.triggered", "type", "price_drop")
                .increment(targetUsers.size.toDouble())
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
            log.info("Restock detected: ${event.productId}")

            // 장바구니에 담은 유저 조회
            val targetUsers = targetResolver.findUsersWithCartItem(event.productId)

            val product = productRepository.findById(event.productId) ?: return

            targetUsers.forEach { userId ->
                notificationProducer.send(
                    NotificationEvent(
                        notificationId = UUID.randomUUID().toString(),
                        userId = userId,
                        productId = event.productId,
                        notificationType = NotificationType.BACK_IN_STOCK,
                        title = "재입고 알림",
                        body = "${product.name}이(가) 다시 입고되었습니다!",
                        data = mapOf("currentStock" to currentStock.toString()),
                        channels = listOf(Channel.PUSH, Channel.SMS),
                        priority = Priority.HIGH,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            Metrics.counter("notifications.triggered", "type", "restock")
                .increment(targetUsers.size.toDouble())
        }
    }
}
```

### 4.3 Target Resolver (대상 유저 추출)

```kotlin
@Component
class TargetResolver(
    private val esClient: ElasticsearchClient
) {
    /**
     * 특정 상품에 관심을 보인 유저 추출
     */
    suspend fun findInterestedUsers(
        productId: String,
        actionTypes: List<ActionType>,
        withinDays: Int,
        limit: Int = 10000
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
                                t.field("actionType")
                                    .terms { tv -> tv.value(actionTypes.map { FieldValue.of(it.name) }) }
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
                        t.field("userId").size(limit)
                    }
                }
        }, Void::class.java)

        return response.aggregations()["users"]
            ?.sterms()?.buckets()?.array()
            ?.map { it.key().stringValue() } ?: emptyList()
    }

    /**
     * 장바구니에 특정 상품을 담은 유저 추출
     */
    suspend fun findUsersWithCartItem(productId: String): List<String> {
        return findInterestedUsers(
            productId = productId,
            actionTypes = listOf(ActionType.ADD_TO_CART),
            withinDays = 7,  // 최근 7일 이내 장바구니
            limit = 5000
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


## 5. 알림 중복 방지

### 5.1 유저별 알림 빈도 제한

```kotlin
@Component
class NotificationRateLimiter(
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) {
    /**
     * 동일 유저 + 동일 상품에 대해 1시간 내 중복 알림 방지
     */
    suspend fun shouldSend(userId: String, productId: String, type: NotificationType): Boolean {
        val key = "notification:sent:$userId:$productId:${type.name}"

        val exists = redisTemplate.hasKey(key).awaitSingle()
        if (exists) {
            return false
        }

        // 1시간 TTL로 키 설정
        redisTemplate.opsForValue()
            .set(key, "1", Duration.ofHours(1))
            .awaitSingle()

        return true
    }

    /**
     * 유저별 일일 알림 횟수 제한 (최대 10회)
     */
    suspend fun checkDailyLimit(userId: String): Boolean {
        val key = "notification:daily:$userId"

        val count = redisTemplate.opsForValue()
            .increment(key)
            .awaitSingle()

        if (count == 1L) {
            // 첫 알림이면 자정까지 TTL 설정
            val now = LocalDateTime.now()
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
            val ttl = Duration.between(now, midnight)
            redisTemplate.expire(key, ttl).awaitSingle()
        }

        return count <= 10
    }
}
```

### 5.2 Event Detector에 Rate Limiter 적용

```kotlin
@Component
class EventDetector(
    private val rateLimiter: NotificationRateLimiter,
    // ... 기타 의존성
) {
    suspend fun detectPriceDrop(event: ProductInventoryEvent) {
        // ... 가격 하락 감지 로직 ...

        targetUsers
            .filter { userId ->
                rateLimiter.shouldSend(userId, event.productId, NotificationType.PRICE_DROP) &&
                rateLimiter.checkDailyLimit(userId)
            }
            .forEach { userId ->
                notificationProducer.send(/* ... */)
            }
    }
}
```


## 6. 알림 이력 저장

```kotlin
@Component
class NotificationHistoryService(
    private val esClient: ElasticsearchClient
) {
    suspend fun save(notification: NotificationEvent, status: SendStatus) {
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


## 7. Phase 4 성공 기준 (Exit Criteria)

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