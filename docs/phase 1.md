# Phase 1 상세 구현: 트래픽 시뮬레이터 (Traffic Simulator)

본 문서는 `REP-Engine`의 원천 데이터 공급원인 트래픽 시뮬레이터의 실제 구현 코드 구조와 핵심 로직을 정의합니다.

## 1. 프로젝트 설정 (build.gradle.kts)

코틀린의 비동기 성능을 극대화하기 위해 `Coroutines`와 `Spring Kafka`를 조합합니다.

```kotlin
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("io.confluent:kafka-avro-serializer:7.5.0")
    implementation("org.apache.avro:avro:1.11.3")
    // 데이터 생성은 Kotlin Random 사용
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

plugins {
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1"
}
```

## 2. 도메인 모델 정의 (Event Schema)

실무에서는 데이터 포맷 변경에 유연하게 대응하기 위해 공통 인터페이스나 스키마를 정의합니다.

```kotlin
enum class ActionType {
    VIEW, CLICK, SEARCH, PURCHASE, ADD_TO_CART, WISHLIST
}

data class UserActionEvent(
    val traceId: String = UUID.randomUUID().toString(),
    val userId: String,
    val productId: String,
    val category: String,
    val actionType: ActionType,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)
```

## 3. 설정값 (application.yml)

시뮬레이터의 동작을 제어하는 설정값입니다.

```yaml
simulator:
  user-count: ${SIMULATOR_USER_COUNT:100}           # 가상 유저 수
  delay-millis: ${SIMULATOR_DELAY_MILLIS:1000}     # 행동 간 지연 (ms)
  topic: user.action.v1                            # Kafka 토픽
  product-count-per-category: ${SIMULATOR_PRODUCT_COUNT:100}  # 카테고리당 상품 수
  enabled: ${SIMULATOR_ENABLED:true}               # 활성화 여부
```

## 4. 핵심 컴포넌트 설계

### 4.1 Kafka Producer Configuration

단순 설정이 아닌, 실무에서 권장되는 **안정성(Reliability)** 옵션을 적용합니다.

- **Acks = All:** 모든 복제본에 기록됨을 보장.

- **Idempotence = True:** 중복 메시지 전송 방지.

- **Linger.ms = 5:** 처리량 향상을 위해 메시지를 약간 모아서 보냄.


### 4.2 UserSession (가상 유저 상태 관리)

각 유저는 고유한 특성(예: 특정 카테고리 선호)을 가지며, 이를 통해 데이터의 편향성을 시뮬레이션합니다.

```kotlin
class UserSession(val userId: String) {
    companion object {
        private val CATEGORIES = listOf(
            "ELECTRONICS", "FASHION", "FOOD", "BEAUTY",
            "SPORTS", "HOME", "BOOKS"
        )

        // 가중치 기반 행동 분포 (실제 서비스 패턴 반영)
        private val ACTION_WEIGHTS = mapOf(
            ActionType.VIEW to 45,        // 45% - 가장 빈번
            ActionType.CLICK to 25,       // 25% - 관심 표현
            ActionType.SEARCH to 10,      // 10% - 검색
            ActionType.ADD_TO_CART to 8,  // 8% - 장바구니
            ActionType.PURCHASE to 5,     // 5% - 구매 (가장 드묾)
            ActionType.WISHLIST to 7      // 7% - 위시리스트
        )
    }

    private val preferredCategory = CATEGORIES.random()

    fun nextAction(): UserActionEvent {
        val category = selectCategory()
        val productId = selectProduct(category)
        val actionType = selectActionType()

        return UserActionEvent.newBuilder()
            .setUserId(userId)
            .setProductId(productId)
            .setCategory(category)
            .setActionType(actionType)
            .build()
    }

    // 70% 확률로 선호 카테고리 선택
    private fun selectCategory(): String {
        return if (Random.nextDouble() < 0.7) preferredCategory
               else CATEGORIES.filter { it != preferredCategory }.random()
    }

    // 상품 ID 형식: PROD-{카테고리3자}-{순번5자리}
    // seed_products.py와 일치하도록 설계
    private fun selectProduct(category: String): String {
        val productNum = Random.nextInt(1, 101)
        return "PROD-${category.take(3)}-${productNum.toString().padStart(5, '0')}"
    }

    // ACTION_WEIGHTS 기반 가중치 랜덤 선택
    private fun selectActionType(): ActionType { /* ... */ }
}
```

### 4.3 Simulator 엔진 (Coroutine 기반)

수천 명의 유저가 동시에 활동하는 상황을 `Dispatcher.Default`를 활용해 비동기로 처리합니다.

```
@Component
class TrafficSimulator(
    private val kafkaTemplate: KafkaTemplate<String, UserActionEvent>  // Avro 타입 사용
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    fun startSimulation(userCount: Int, delayMillis: Long) {
        repeat(userCount) { i ->
            scope.launch {
                val session = UserSession("USER-$i")
                while (isActive) {
                    val event = session.nextAction()
                    sendToKafka(event)
                    delay(Random.nextLong(delayMillis, delayMillis * 2))
                }
            }
        }
    }
}
```

## 5. 실무형 에러 핸들링 및 로깅

메시지 전송 실패 시 단순히 로그만 찍는 것이 아니라, 재시도 전략이나 메트릭 수집을 고려합니다.

```
private fun sendToKafka(event: UserActionEvent) {
    // Avro 직렬화는 KafkaAvroSerializer가 자동 처리
    val future = kafkaTemplate.send("user.action.v1", event.userId, event)

    future.whenComplete { result, ex ->
        if (ex == null) {
            // 성공 로그 (실무에서는 성공 로그는 디버그 레벨로 관리)
            log.debug("Sent event: ${result.recordMetadata.offset()}")
        } else {
            // 실패 처리: 실무에서는 별도 Error Topic이나 Metrics(Micrometer)로 전송
            log.error("Failed to send event", ex)
        }
    }
}
```

## 6. 단계별 구현 가이드

1. **Project Initialization:** Spring Initializr에서 Kotlin, Spring Kafka 선택하여 프로젝트 생성.

2. **Infrastructure Check:** 이전 문서의 Docker Compose가 띄워져 있는지 확인.

3. **Data Class & Faker:** 유저와 상품 정보를 생성할 Faker 라이브러리 연동.

4. **Simulation Logic:** Coroutine을 활용해 무한 루프 내에서 가상 행동 생성.

5. **Kibana 모니터링:** 데이터가 인덱싱되는 패턴을 관찰하며 `userCount` 조절.


## 7. 다음 단계 예고

시뮬레이터가 완성되어 데이터가 Kafka로 흐르기 시작하면, **Phase 2: Consumer & ElasticSearch Indexing** 설계를 통해 이 데이터를 어떻게 검색 엔진에 효율적으로 집어넣을지 다룹니다.