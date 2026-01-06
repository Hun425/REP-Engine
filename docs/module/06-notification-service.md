# notification-service 모듈 설명서

## 이 모듈이 하는 일 (한 줄 요약)

**가격 하락, 재입고 등 상품 변동을 감지하여 관심 유저에게 알림을 발송하는 서비스**

---

## 비유로 이해하기

백화점에서 **할인 행사 안내원** 같은 역할을 합니다.

1. 상품 가격이 떨어지거나 재입고되면 (Kafka에서 이벤트 수신)
2. 그 상품에 관심 보인 고객 목록을 찾아서 (ES에서 행동 기록 조회)
3. "좋아하시던 상품이 할인 중이에요!"라고 알림을 보냅니다 (Push/SMS/Email)

---

## 파일 구조

```
notification-service/
├── build.gradle.kts
└── src/main/
    ├── kotlin/com/rep/notification/
    │   ├── NotificationServiceApplication.kt  # 앱 시작점
    │   ├── config/
    │   │   ├── NotificationProperties.kt      # 설정값 클래스
    │   │   ├── KafkaConsumerConfig.kt         # Kafka Consumer 설정
    │   │   ├── KafkaProducerConfig.kt         # Kafka Producer 설정
    │   │   ├── ElasticsearchConfig.kt         # ES 연결 설정
    │   │   ├── RedisConfig.kt                 # Redis 연결 설정
    │   │   └── DispatcherConfig.kt            # Virtual Thread 설정
    │   ├── consumer/
    │   │   ├── InventoryEventConsumer.kt      # 재고/가격 변동 수신
    │   │   └── PushSenderSimulator.kt         # 알림 발송 시뮬레이터
    │   ├── service/
    │   │   ├── EventDetector.kt               # 알림 조건 감지
    │   │   ├── TargetResolver.kt              # 대상 유저 추출
    │   │   ├── NotificationProducer.kt        # 알림 메시지 발행
    │   │   ├── NotificationRateLimiter.kt     # 중복/과다 발송 방지
    │   │   └── NotificationHistoryService.kt  # 발송 이력 저장
    │   └── repository/
    │       └── ProductRepository.kt           # 상품 정보 조회
    └── resources/
        └── application.yml
```

---

## 전체 동작 흐름 (꼭 이해하세요!)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         알림 서비스 처리 흐름                             │
└─────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                     Kafka 토픽                                    │
│  product.inventory.v1 (가격/재고 변동)                            │
└─────────────────────────────┬────────────────────────────────────┘
                              │
                              │ 1. 이벤트 수신
                              ▼
                    ┌──────────────────┐
                    │ InventoryEvent   │
                    │ Consumer         │
                    └────────┬─────────┘
                             │
                             │ 2. 이벤트 타입별 분기
                             ▼
                    ┌──────────────────┐
                    │  EventDetector   │
                    │  (조건 감지)     │
                    └────────┬─────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
        ┌──────────┐  ┌──────────┐  ┌──────────────┐
        │ 가격하락  │  │ 재입고   │  │ (기타 무시)  │
        │ 10%이상? │  │ 0→1+?   │  │              │
        └────┬─────┘  └────┬─────┘  └──────────────┘
             │             │
             │             │ 3. 대상 유저 조회
             ▼             ▼
        ┌──────────────────────┐
        │   TargetResolver     │
        │ (ES에서 관심 유저    │
        │  집계 쿼리)          │
        └──────────┬───────────┘
                   │
                   │ 4. Rate Limit 체크
                   ▼
        ┌──────────────────────┐
        │ NotificationRate     │
        │ Limiter (Redis)      │
        │ - 일일 10회 제한     │
        │ - 1시간 중복 방지    │
        └──────────┬───────────┘
                   │
                   │ 5. 알림 발행
                   ▼
        ┌──────────────────────┐
        │ NotificationProducer │
        │ (Kafka로 전송)       │
        └──────────┬───────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Kafka 토픽                                    │
│  notification.push.v1 (알림 발송)                                 │
└─────────────────────────────┬────────────────────────────────────┘
                              │
                              │ 6. 알림 수신
                              ▼
                    ┌──────────────────┐
                    │ PushSender       │
                    │ Simulator        │
                    │ (발송 시뮬레이션)│
                    └────────┬─────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
        ┌──────────┐  ┌──────────┐  ┌──────────┐
        │  PUSH    │  │   SMS    │  │  IN_APP  │
        │  (FCM)   │  │ Gateway  │  │ WebSocket│
        └──────────┘  └──────────┘  └──────────┘
                             │
                             │ 7. 이력 저장
                             ▼
                    ┌──────────────────┐
                    │ NotificationHistory│
                    │ Service (ES 저장) │
                    └──────────────────┘
```

---

## 핵심 파일 상세 설명

### 1. InventoryEventConsumer.kt (이벤트 수신)

**역할**: Kafka에서 재고/가격 변동 이벤트를 수신

```kotlin
@Component
class InventoryEventConsumer(
    private val eventDetector: EventDetector
) {

    @KafkaListener(
        topics = ["product.inventory.v1"],
        groupId = "notification-consumer-group",
        containerFactory = "inventoryListenerContainerFactory"  // concurrency=3
    )
    fun consume(
        record: ConsumerRecord<String, ProductInventoryEvent>,
        acknowledgment: Acknowledgment
    ) {
        val event = record.value()

        when (event.eventType) {
            PRICE_CHANGE -> eventDetector.detectPriceDrop(event)
            STOCK_CHANGE -> eventDetector.detectRestock(event)
            else -> { /* 무시 */ }
        }

        acknowledgment.acknowledge()
    }
}
```

#### 이벤트 타입별 처리

| 이벤트 타입 | 조건 | 알림 유형 |
|------------|------|----------|
| `PRICE_CHANGE` | 10% 이상 하락 | `PRICE_DROP` |
| `STOCK_CHANGE` | 재고 0 → 1+ | `BACK_IN_STOCK` |

---

### 2. EventDetector.kt (알림 조건 감지)

**역할**: 알림 발송 조건을 확인하고 대상 유저에게 알림 발송

```kotlin
@Component
class EventDetector(
    private val targetResolver: TargetResolver,
    private val notificationProducer: NotificationProducer,
    private val rateLimiter: NotificationRateLimiter,
    private val productRepository: ProductRepository
) {

    suspend fun detectPriceDrop(event: ProductInventoryEvent) {
        val previousPrice = event.previousPrice ?: return
        val currentPrice = event.currentPrice ?: return

        // 가격 하락 비율 계산
        val dropPercentage = ((previousPrice - currentPrice) / previousPrice * 100).toInt()

        // 10% 이상 하락했나?
        if (dropPercentage >= 10) {

            // 1. 관심 유저 찾기 (VIEW, CLICK, ADD_TO_CART 한 유저)
            val targetUsers = targetResolver.findInterestedUsers(
                productId = event.productId,
                actionTypes = listOf("VIEW", "CLICK", "ADD_TO_CART"),
                withinDays = 30
            )

            // 2. 각 유저에게 알림 발송
            for (userId in targetUsers) {
                // Rate Limit 체크
                if (!rateLimiter.canSend(userId, productId, "PRICE_DROP")) {
                    continue  // 건너뛰기
                }

                // 알림 생성 및 발송
                val notification = NotificationEvent.newBuilder()
                    .setUserId(userId)
                    .setProductId(productId)
                    .setNotificationType(NotificationType.PRICE_DROP)
                    .setTitle("가격이 떨어졌어요!")
                    .setBody("${productName}이(가) ${dropPercentage}% 할인 중입니다!")
                    .build()

                notificationProducer.send(notification)
            }
        }
    }
}
```

#### 가격 하락 감지 흐름

```
상품 가격 변동: 100,000원 → 85,000원

1. 하락 비율 계산: (100000-85000)/100000 * 100 = 15%
2. 15% >= 10%? → YES! 알림 발송 대상
3. 관심 유저 조회 (30일 내 VIEW/CLICK/ADD_TO_CART)
4. Rate Limit 체크 후 알림 발송
```

---

### 3. TargetResolver.kt (대상 유저 추출)

**역할**: ES에서 특정 상품에 관심 보인 유저 목록 집계

```kotlin
@Component
class TargetResolver(
    private val esClient: ElasticsearchClient
) {

    fun findInterestedUsers(
        productId: String,
        actionTypes: List<String>,
        withinDays: Int
    ): List<String> {

        // ES 집계 쿼리
        val response = esClient.search({ s ->
            s.index("user_behavior_index")
                .size(0)  // 문서는 안 가져오고 집계만
                .query { q ->
                    q.bool { b ->
                        // 상품 ID 필터
                        b.must { m -> m.term { t -> t.field("productId").value(productId) } }
                        // 행동 유형 필터 (VIEW, CLICK, ADD_TO_CART)
                        b.must { m -> m.terms { t -> t.field("actionType")... } }
                        // 기간 필터 (최근 N일)
                        b.must { m -> m.range { r -> r.field("timestamp").gte("now-${withinDays}d") } }
                    }
                }
                .aggregations("users") { agg ->
                    agg.terms { t -> t.field("userId").size(10000) }  // 최대 1만 명
                }
        })

        // 결과에서 유저 ID 추출
        return response.aggregations()["users"]
            ?.sterms()?.buckets()?.array()
            ?.map { it.key().stringValue() } ?: emptyList()
    }
}
```

#### ES 집계 쿼리 시각화

```
user_behavior_index에서:

┌────────────────────────────────────────────────────┐
│ userId=USER-001, productId=PROD-001, action=VIEW   │
│ userId=USER-002, productId=PROD-001, action=CLICK  │
│ userId=USER-001, productId=PROD-001, action=CLICK  │
│ userId=USER-003, productId=PROD-002, action=VIEW   │ ← 다른 상품
│ userId=USER-004, productId=PROD-001, action=CART   │
└────────────────────────────────────────────────────┘

쿼리: productId=PROD-001, actionTypes=[VIEW,CLICK,CART]

결과: [USER-001, USER-002, USER-004]  ← 이 유저들에게 알림!
```

---

### 4. NotificationRateLimiter.kt (중복/과다 방지)

**역할**: Redis를 사용하여 알림 과다 발송 방지

```kotlin
@Component
class NotificationRateLimiter(
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) {

    companion object {
        private const val SENT_KEY_PREFIX = "notification:sent:"
        private const val DAILY_KEY_PREFIX = "notification:daily:"
    }

    /**
     * 발송 가능 여부 확인
     */
    suspend fun canSend(userId: String, productId: String, type: String): Boolean {
        return shouldSend(userId, productId, type) && checkDailyLimit(userId)
    }

    /**
     * 중복 알림 방지 (동일 유저+상품+타입에 1시간 내 중복 차단)
     */
    suspend fun shouldSend(userId: String, productId: String, type: String): Boolean {
        val key = "$SENT_KEY_PREFIX$userId:$productId:$type"

        val exists = redisTemplate.hasKey(key).awaitSingle()
        if (exists) return false  // 이미 발송함

        // 1시간 TTL로 키 설정
        redisTemplate.opsForValue()
            .set(key, "1", Duration.ofHours(1))
            .awaitSingle()

        return true
    }

    /**
     * 일일 발송 제한 (유저당 하루 10회)
     */
    suspend fun checkDailyLimit(userId: String): Boolean {
        val key = "$DAILY_KEY_PREFIX$userId"

        val count = redisTemplate.opsForValue().increment(key).awaitSingle()

        if (count == 1L) {
            // 첫 알림이면 자정까지 TTL 설정
            redisTemplate.expire(key, calculateTtlUntilMidnight()).awaitSingle()
        }

        return count <= 10  // 10회 초과 시 차단
    }
}
```

#### Redis 키 구조

```
# 중복 방지 (1시간 TTL)
notification:sent:USER-001:PROD-001:PRICE_DROP → "1"
notification:sent:USER-001:PROD-002:PRICE_DROP → "1"

# 일일 제한 (자정까지 TTL)
notification:daily:USER-001 → "3"   (오늘 3번 받음)
notification:daily:USER-002 → "10"  (오늘 10번 = 더 이상 안 보냄)
```

---

### 5. NotificationProducer.kt (알림 발행)

**역할**: 알림 메시지를 Kafka로 발행

```kotlin
@Component
class NotificationProducer(
    private val kafkaTemplate: KafkaTemplate<String, NotificationEvent>
) {

    fun send(notification: NotificationEvent) {
        kafkaTemplate.send(
            "notification.push.v1",
            notification.userId.toString(),  // 파티션 키
            notification
        ).whenComplete { result, ex ->
            if (ex != null) {
                log.error { "Failed to send: ${notification.notificationId}" }
            } else {
                log.debug { "Sent: ${notification.notificationId}" }
            }
        }
    }
}
```

---

### 6. PushSenderSimulator.kt (발송 시뮬레이터)

**역할**: 알림을 실제 채널로 발송 (시뮬레이션)

```kotlin
@Component
class PushSenderSimulator(
    private val historyService: NotificationHistoryService
) {

    @KafkaListener(
        topics = ["notification.push.v1"],
        groupId = "push-sender-group",
        containerFactory = "notificationListenerContainerFactory"  // concurrency=6
    )
    fun consume(record: ConsumerRecord<String, NotificationEvent>, ack: Acknowledgment) {
        val notification = record.value()

        // 각 채널별로 발송
        notification.channels.forEach { channel ->
            when (channel) {
                PUSH -> simulatePush(notification)     // FCM/APNs
                SMS -> simulateSms(notification)       // SMS Gateway
                EMAIL -> simulateEmail(notification)   // SendGrid/SES
                IN_APP -> simulateInApp(notification)  // WebSocket
            }
        }

        // 이력 저장 (ES)
        historyService.save(notification, SendStatus.SENT)

        ack.acknowledge()
    }

    private fun simulatePush(notification: NotificationEvent) {
        log.info {
            """
            [PUSH] Sending to ${notification.userId}
              Title: ${notification.title}
              Body: ${notification.body}
            """.trimMargin()
        }
        // 실제 구현: FCM/APNs 호출
    }
}
```

#### 실제 운영에서의 채널 연동

| 채널 | 실제 서비스 | 용도 |
|------|-----------|------|
| PUSH | FCM, APNs | 모바일 앱 알림 |
| SMS | Twilio, AWS SNS | 긴급 알림 |
| EMAIL | SendGrid, AWS SES | 마케팅, 상세 정보 |
| IN_APP | WebSocket | 실시간 웹 알림 |

---

## 알림 유형별 정리

| 유형 | 트리거 조건 | 대상 유저 | 채널 | 우선순위 |
|------|-----------|----------|------|---------|
| `PRICE_DROP` | 가격 10%+ 하락 | VIEW/CLICK/CART 유저 (30일) | PUSH, IN_APP | HIGH |
| `BACK_IN_STOCK` | 재고 0→1+ | ADD_TO_CART 유저 (7일) | PUSH, SMS | HIGH |

---

## 설정 파일 (application.yml)

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: notification-consumer-group
      properties:
        schema.registry.url: http://localhost:8081

  elasticsearch:
    uris: http://localhost:9200

  data:
    redis:
      host: localhost
      port: 6379

notification:
  # 가격 하락 알림 임계값 (%)
  price-drop-threshold: 10

  # 대상 유저 조회 설정
  target-user-limit: 10000          # 최대 대상 유저 수
  interested-user-days: 30          # 관심 유저 조회 기간
  cart-user-days: 7                 # 장바구니 유저 조회 기간

  # Rate Limiting
  daily-limit-per-user: 10          # 유저당 일일 최대 알림
  duplicate-prevention-hours: 1     # 중복 알림 방지 기간

  # Kafka 토픽
  inventory-topic: product.inventory.v1
  notification-topic: notification.push.v1

server:
  port: 8083
```

---

## Kafka Consumer 동시성 설정

```kotlin
// KafkaConsumerConfig.kt

// product.inventory.v1 → 3 파티션, concurrency=3
@Bean
fun inventoryListenerContainerFactory() = ...apply {
    setConcurrency(3)
}

// notification.push.v1 → 6 파티션, concurrency=6
@Bean
fun notificationListenerContainerFactory() = ...apply {
    setConcurrency(6)
}
```

---

## 메트릭 (모니터링 지표)

| 메트릭 이름 | 의미 |
|------------|------|
| `notification.event.detected` | 감지된 알림 이벤트 수 (tag: type) |
| `notification.triggered` | 발송된 알림 수 |
| `notification.rate.limited` | Rate Limit으로 차단된 수 |
| `notification.rate.duplicate.blocked` | 중복 알림 차단 수 |
| `notification.rate.daily.blocked` | 일일 제한 초과 차단 수 |
| `notification.target.found` | 대상 유저 조회 수 |
| `notification.send.success` | Kafka 발행 성공 수 |
| `notification.push.sent` | 실제 발송 시뮬레이션 수 |
| `notification.push.channel` | 채널별 발송 수 (tag: channel) |
| `notification.history.save.success` | 이력 저장 성공 수 |

---

## 실행 방법

```bash
# 인프라 시작
cd docker
docker-compose up -d

# 토픽 생성 (notification.push.v1 포함)
./init-topics.sh

# notification-service 실행
cd ..
./gradlew :notification-service:bootRun
```

---

## 테스트 방법

### 1. 가격 변동 이벤트 직접 발행 (테스트용)

```bash
# Kafka Producer로 테스트 이벤트 전송
# (실제 운영에서는 외부 시스템에서 발행)
```

### 2. 로그 확인

```bash
# 알림 감지 로그
grep "Price drop detected" logs/notification-service.log

# 발송 시뮬레이션 로그
grep "\[PUSH\]" logs/notification-service.log
```

### 3. ES에서 발송 이력 확인

```bash
curl "http://localhost:9200/notification_history_index/_search?pretty"
```

---

## 핵심 요약

| 항목 | 내용 |
|------|------|
| 역할 | 상품 변동 감지 → 관심 유저 알림 발송 |
| 포트 | 8083 |
| 입력 토픽 | `product.inventory.v1` |
| 출력 토픽 | `notification.push.v1` |
| 알림 유형 | PRICE_DROP, BACK_IN_STOCK |
| Rate Limit | 일일 10회, 1시간 중복 방지 |
| 대상 유저 조회 | ES 집계 쿼리 (최대 1만 명) |
| 발송 채널 | PUSH, SMS, EMAIL, IN_APP |
| 이력 저장 | ES `notification_history_index` |
