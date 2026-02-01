# simulator 모듈 설명서

## 이 모듈이 하는 일 (한 줄 요약)

**가짜 유저들이 쇼핑하는 것처럼 행동 데이터를 생성해서 Kafka로 보내는 모듈**

---

## 비유로 이해하기

새로 만든 쇼핑몰 시스템을 테스트하고 싶은데, 아직 실제 유저가 없어요.
그래서 **가짜 유저 100명이 상품을 보고, 클릭하고, 구매하는 척**하는 프로그램을 만든 겁니다.

마치 영화 촬영할 때 엑스트라 배우들이 걸어다니는 것처럼요!

---

## 파일 구조

```
simulator/
├── build.gradle.kts
└── src/main/
    ├── kotlin/com/rep/simulator/
    │   ├── SimulatorApplication.kt      # 앱 시작점
    │   ├── config/
    │   │   ├── SimulatorProperties.kt   # 설정값 클래스
    │   │   ├── KafkaProducerConfig.kt   # Kafka 연결 설정
    │   │   └── WebConfig.kt             # CORS 설정
    │   ├── controller/
    │   │   └── SimulatorController.kt   # REST API 컨트롤러
    │   ├── domain/
    │   │   └── UserSession.kt           # 가짜 유저 1명의 행동
    │   └── service/
    │       └── TrafficSimulator.kt      # 시뮬레이터 본체
    └── resources/
        └── application.yml              # 설정 파일
```

---

## 핵심 파일 상세 설명

### 1. SimulatorApplication.kt (시작점)

**역할**: 프로그램이 시작되면 제일 먼저 실행되는 파일

```kotlin
@SpringBootApplication
class SimulatorApplication {

    @Bean
    fun run(simulator: TrafficSimulator, properties: SimulatorProperties): CommandLineRunner = CommandLineRunner {
        if (properties.enabled) {
            // 시뮬레이터 시작!
            simulator.startSimulation()
        }
    }
}

fun main(args: Array<String>) {
    runApplication<SimulatorApplication>(*args)  // 앱 시작
}
```

**흐름**:
1. `main()` 함수 실행
2. Spring Boot가 모든 컴포넌트 준비
3. `run()` 함수 실행
4. `simulator.startSimulation()` 호출 → 시뮬레이션 시작!

---

### 2. SimulatorProperties.kt (설정값)

**역할**: 시뮬레이터의 설정값들을 담는 클래스

```kotlin
@ConfigurationProperties(prefix = "simulator")
data class SimulatorProperties(
    val userCount: Int = 100,           // 가짜 유저 수
    val delayMillis: Long = 1000,       // 행동 간격 (밀리초)
    val topic: String = "user.action.v1",  // Kafka 토픽명
    val enabled: Boolean = true,        // 시뮬레이터 ON/OFF
    val productCountPerCategory: Int = 100  // 카테고리별 상품 수
)
```

**설정 변경 방법**:

방법 1: `application.yml` 파일 수정
```yaml
simulator:
  user-count: 200      # 200명으로 변경
  delay-millis: 500    # 0.5초 간격으로 변경
```

방법 2: 환경 변수 사용
```bash
export SIMULATOR_USER_COUNT=200
export SIMULATOR_DELAY_MILLIS=500
```

---

### 3. UserSession.kt (가짜 유저 1명)

**역할**: 가짜 유저 1명이 어떻게 행동할지 결정

```kotlin
class UserSession(
    val userId: String,
    productCountPerCategory: Int
) {
    // 유저가 할 수 있는 행동들과 확률
    companion object {
        val CATEGORIES = listOf("ELECTRONICS", "FASHION", "FOOD", "BEAUTY", "SPORTS", "HOME", "BOOKS")
        val ACTION_WEIGHTS = mapOf(
            ActionType.VIEW to 45,        // 45% - 가장 빈번
            ActionType.CLICK to 25,       // 25% - 관심 표현
            ActionType.SEARCH to 10,      // 10% - 검색
            ActionType.ADD_TO_CART to 8,  // 8% - 장바구니
            ActionType.PURCHASE to 5,     // 5% - 구매 (가장 드묾)
            ActionType.WISHLIST to 7      // 7% - 위시리스트
        )
    }

    // 유저별 선호 카테고리 (한 번 정해지면 유지)
    private val preferredCategory: String = CATEGORIES.random()

    fun nextAction(): UserActionEvent {
        // 1. 어떤 카테고리에서 활동할지 선택
        //    70% 확률로 선호 카테고리, 30% 확률로 다른 카테고리
        val category = selectCategory()

        // 2. 어떤 상품을 볼지 랜덤 선택
        // 형식: PROD-{카테고리3글자}-{00001~productCountPerCategory}
        val productNum = Random.nextInt(1, productCountPerCategory + 1)
        val productId = "PROD-${category.take(3)}-${productNum.toString().padStart(5, '0')}"

        // 3. 어떤 행동을 할지 확률에 따라 선택
        val actionType = selectAction()  // VIEW 45%, CLICK 25%, ...

        // 4. 이벤트 객체 생성해서 반환
        return UserActionEvent.newBuilder()
            .setTraceId(UUID.randomUUID().toString())
            .setUserId(userId)
            .setProductId(productId)
            .setCategory(category)
            .setActionType(actionType)
            .setTimestamp(Instant.now())
            .build()
    }
}
```

**행동 선택 확률** (100번 행동하면):
```
VIEW: 45번 (그냥 봄)
CLICK: 25번 (클릭)
SEARCH: 10번 (검색)
ADD_TO_CART: 8번 (장바구니)
WISHLIST: 7번 (찜)
PURCHASE: 5번 (구매)
```

이건 실제 쇼핑몰 데이터를 참고해서 만든 비율이에요.
보통 보기만 하고 구매까지 가는 사람은 적으니까요!

#### 선호 카테고리 (70% 로직)

각 유저는 생성 시 무작위로 선호 카테고리가 정해지고, 70% 확률로 해당 카테고리에서 행동합니다:

```kotlin
private val preferredCategory: String = CATEGORIES.random()

private fun selectCategory(): String {
    return if (Random.nextDouble() < 0.7) {
        preferredCategory  // 70% 확률로 선호 카테고리
    } else {
        CATEGORIES.filter { it != preferredCategory }.random()  // 30% 확률로 다른 카테고리
    }
}
```

**예시**: USER-000001의 선호 카테고리가 "ELECTRONICS"라면
- 70%는 ELECTRONICS 상품 조회
- 30%는 FASHION, FOOD 등 다른 카테고리 상품 조회

이렇게 하면 실제 유저처럼 특정 분야에 관심이 집중된 행동 패턴을 시뮬레이션할 수 있습니다.

#### 고급 기능: 유저 세션 연속성

실제 쇼핑 행동을 더 현실적으로 시뮬레이션하기 위한 기능들:

**1. 최근 본 상품 추적 (리마케팅 시뮬레이션)**

```kotlin
// UserSession.kt
private val recentProducts = mutableListOf<String>()  // 최근 본 상품 20개 저장

fun selectProduct(): String {
    // 30% 확률로 최근 본 상품 재방문 (리마케팅 효과)
    if (recentProducts.isNotEmpty() && Random.nextFloat() < 0.3f) {
        return recentProducts.random()
    }
    // 70% 확률로 새 상품 선택
    val newProduct = generateProductId(category)
    recentProducts.add(newProduct)
    if (recentProducts.size > 20) recentProducts.removeAt(0)
    return newProduct
}
```

**2. 가격대 선호도**

```kotlin
// UserSession.kt
// 유저별 선호 가격대 (10,000 ~ 680,000)
val preferredPriceRange: IntRange = run {
    val base = Random.nextInt(1, 50) * 10000  // 10,000 ~ 490,000
    base..(base + Random.nextInt(5, 20) * 10000)  // +50,000 ~ 190,000 추가
}

// PURCHASE 메타데이터에 선호 가격대 반영
metadata["price"] = Random.nextInt(preferredPriceRange.first, preferredPriceRange.last).toString()
```

**3. 행동별 메타데이터 생성**

각 행동 유형에 따라 현실적인 메타데이터를 생성합니다:

| 행동 | 메타데이터 필드 | 예시 값 |
|------|---------------|---------|
| SEARCH | `searchQuery`, `resultCount` | "갤럭시 케이스", 42 |
| VIEW | `referrer`, `viewDurationMs` | "home", 15000 (1000~30000ms) |
| CLICK | `position` | 3 |
| PURCHASE | `quantity`, `price` | 1, 89000 |
| ADD_TO_CART | `quantity` | 2 |
| WISHLIST | `source` | "product_detail" |

```kotlin
// UserSession.kt
private fun generateMetadata(actionType: ActionType): Map<String, String> {
    return when (actionType) {
        SEARCH -> mapOf(
            "searchQuery" to generateSearchQuery(category),
            "resultCount" to Random.nextInt(10, 100).toString()
        )
        VIEW -> mapOf(
            "referrer" to listOf("home", "search", "recommendation", "category").random(),
            "viewDurationMs" to Random.nextInt(1000, 30000).toString()  // 1~30초
        )
        CLICK -> mapOf("position" to Random.nextInt(1, 20).toString())
        PURCHASE -> mapOf(
            "quantity" to Random.nextInt(1, 3).toString(),
            "price" to Random.nextInt(preferredPriceRange).toString()
        )
        ADD_TO_CART -> mapOf("quantity" to Random.nextInt(1, 5).toString())
        WISHLIST -> mapOf("source" to listOf("product_detail", "recommendation", "search_result").random())
    }
}
```

---

### 4. TrafficSimulator.kt (시뮬레이터 본체)

**역할**: 여러 가짜 유저를 동시에 돌리는 핵심 로직

```kotlin
@Service
class TrafficSimulator(
    private val kafkaTemplate: KafkaTemplate<String, UserActionEvent>,
    private val properties: SimulatorProperties
) {
    // Virtual Thread 기반 Dispatcher (동시에 수만 명 처리 가능!)
    private val virtualThreadDispatcher = Executors.newVirtualThreadPerTaskExecutor()
        .asCoroutineDispatcher()

    fun startSimulation(userCount: Int = properties.userCount) {
        // 100명의 유저를 동시에 시작
        (1..userCount).forEach { i ->
            async {
                val session = UserSession(userId = "USER-${i.toString().padStart(6, '0')}")
                runUserSession(session)  // 각 유저 세션 실행
            }
        }
    }

    private suspend fun runUserSession(session: UserSession) {
        while (true) {  // 계속 반복
            val event = session.nextAction()  // 다음 행동 생성
            sendToKafka(event)                // Kafka로 전송
            delay(Random.nextLong(1000, 2000))  // 1~2초 대기
        }
    }

    private fun sendToKafka(event: UserActionEvent) {
        kafkaTemplate.send(properties.topic, event.userId, event)
    }
}
```

#### Virtual Thread가 뭔가요?

**비유**: 식당에서 손님 100명이 동시에 주문했을 때

**기존 방식 (일반 Thread)**:
- 웨이터 1명이 손님 1명 담당
- 손님 100명이면 웨이터 100명 필요
- 웨이터 고용 비용 엄청 비쌈 (메모리 많이 씀)

**Virtual Thread 방식**:
- 웨이터 10명이 손님 100명 담당
- 손님이 음식 기다리는 동안 다른 손님 주문 받음
- 훨씬 효율적! (메모리 적게 씀)

Java 21+에서 추가된 기능으로, 적은 리소스로 많은 동시 작업이 가능합니다.

#### Graceful Shutdown (우아한 종료)

시뮬레이터가 종료될 때 진행 중인 이벤트를 안전하게 마무리합니다.

```kotlin
class TrafficSimulator(...) {
    // Graceful shutdown 관련 필드
    private val isShuttingDown = AtomicBoolean(false)
    private val pendingEvents = AtomicInteger(0)
    private val simulationLock = ReentrantLock()

    fun stopSimulation() {
        // 1. shutdown 플래그 설정 (새 이벤트 생성 중단)
        isShuttingDown.set(true)

        // 2. 코루틴 취소
        simulationJob?.cancel()

        // 3. in-flight 이벤트 완료 대기 (최대 5초)
        val maxWaitMs = 5000L
        while (pendingEvents.get() > 0 && 시간 < maxWaitMs) {
            Thread.sleep(100)
        }

        // 4. Kafka Producer flush
        kafkaTemplate.flush()
    }

    @PreDestroy
    fun cleanup() {
        stopSimulation()
        scope.cancel()
        virtualThreadDispatcher.close()
    }
}
```

**5단계 종료 프로세스:**
1. `isShuttingDown` 플래그 설정 → 새 이벤트 생성 중단
2. 코루틴 Job 취소
3. in-flight 이벤트 완료 대기 (최대 5초)
4. Kafka Producer flush
5. Dispatcher 종료

#### 메트릭 (Prometheus)

| 메트릭명 | 타입 | 설명 |
|---------|------|------|
| `simulator.events.sent` | Counter | 전송 성공 이벤트 수 |
| `simulator.events.failed` | Counter | 전송 실패 이벤트 수 |
| `simulator.sessions.active` | Gauge | 현재 활성 세션 수 |

```kotlin
// 메트릭 정의
private val sentCounter = Counter.builder("simulator.events.sent")
    .tag("topic", properties.topic)
    .register(meterRegistry)

private val failedCounter = Counter.builder("simulator.events.failed")
    .tag("topic", properties.topic)
    .register(meterRegistry)

init {
    Gauge.builder("simulator.sessions.active") { activeSessionCount.get() }
        .description("Number of active user sessions")
        .register(meterRegistry)
}
```

---

### 5. KafkaProducerConfig.kt (Kafka 설정)

**역할**: Kafka에 메시지를 보내는 방법 설정

```kotlin
@Configuration
class KafkaProducerConfig(
    @Value("\${spring.kafka.bootstrap-servers}")
    private val bootstrapServers: String,

    @Value("\${spring.kafka.producer.properties.schema.registry.url}")
    private val schemaRegistryUrl: String
) {

    @Bean
    fun producerFactory(): ProducerFactory<String, UserActionEvent> {
        val configProps = mapOf(
            // Kafka 서버 주소 (환경변수로 주입)
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,

            // 키 직렬화: 문자열 그대로
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,

            // 값 직렬화: Avro 형식으로
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to KafkaAvroSerializer::class.java,

            // 안정성 설정
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ProducerConfig.RETRIES_CONFIG to 3,
            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION to 5,  // 순서 보장

            // 성능 설정
            ProducerConfig.LINGER_MS_CONFIG to 5,
            ProducerConfig.BATCH_SIZE_CONFIG to 16384,

            // Schema Registry 주소 (환경변수로 주입)
            KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl
        )
        return DefaultKafkaProducerFactory(configProps)
    }
}
```

#### 주요 설정 설명

| 설정 | 값 | 의미 |
|------|-----|------|
| `acks=all` | 모든 브로커 확인 | 메시지 유실 방지 (안전!) |
| `idempotence=true` | 중복 방지 ON | 같은 메시지 2번 안 보냄 |
| `retries=3` | 3번 재시도 | 일시적 오류 극복 |
| `max.in.flight=5` | 5개 병렬 | 순서 보장 + 성능 (idempotence 필수) |
| `linger.ms=5` | 5ms 대기 | 모아서 보내기 (효율!) |
| `batch.size=16384` | 16KB | 한번에 보낼 양 |

> **Note:** 서버 주소와 Schema Registry URL은 `application.yml`에서 환경변수로 주입받습니다. 하드코딩하지 마세요!

---

### 6. application.yml (설정 파일)

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
      acks: all
      properties:
        schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}

simulator:
  user-count: ${SIMULATOR_USER_COUNT:100}     # 가짜 유저 수
  delay-millis: ${SIMULATOR_DELAY_MILLIS:1000} # 1초 간격
  topic: user.action.v1    # 보낼 토픽
  enabled: ${SIMULATOR_ENABLED:true}          # 시뮬레이터 ON

# Actuator (메트릭/헬스체크)
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics

server:
  port: ${SERVER_PORT:8084}
```

### 7. Docker 프로필 설정

Docker 환경에서는 `docker` 프로필이 활성화되어 별도 설정이 적용됩니다.

```yaml
---
spring:
  config:
    activate:
      on-profile: docker

  kafka:
    bootstrap-servers: kafka:29092  # Docker 네트워크 내 Kafka 주소
    producer:
      properties:
        schema.registry.url: http://schema-registry:8081

# OpenTelemetry Tracing (Jaeger 연동)
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0  # 100% 샘플링 (개발용)
  otlp:
    tracing:
      endpoint: http://jaeger:4318/v1/traces
```

#### Docker vs 로컬 환경 비교

| 설정 | 로컬 | Docker |
|------|------|--------|
| Kafka 주소 | `localhost:9092` | `kafka:29092` |
| Schema Registry | `http://localhost:8081` | `http://schema-registry:8081` |
| 트레이싱 | 비활성화 | Jaeger 연동 |
| 포트 | 8084 | 8084 |

---

## 전체 동작 흐름

```
┌────────────────────────────────────────────────────────────┐
│                    시뮬레이터 동작 흐름                      │
└────────────────────────────────────────────────────────────┘

1. 앱 시작
   │
   ▼
2. SimulatorApplication.run() 실행
   │
   ▼
3. TrafficSimulator.startSimulation() 호출
   │
   ├──▶ 유저 1 (USER-000001) 생성 ──▶ 무한 반복
   │                                    │
   ├──▶ 유저 2 (USER-000002) 생성 ──▶  ├─ nextAction() 호출
   │                                    │    (랜덤 행동 생성)
   ├──▶ 유저 3 (USER-000003) 생성 ──▶  │
   │         ...                        ├─ sendToKafka() 호출
   │                                    │    (Kafka로 전송)
   └──▶ 유저 100 (USER-000100) 생성 ─▶ │
                                        └─ delay(1~2초) 대기
                                           └─ 다시 위로 반복

4. Kafka 토픽 "user.action.v1"에 이벤트 쌓임
   │
   ▼
5. behavior-consumer가 이벤트 소비
```

---

## 실행 방법

### 로컬 실행
```bash
# 1. 먼저 인프라 시작 (Kafka, Schema Registry 등)
cd docker
docker-compose up -d

# 2. 토픽 생성
./init-topics.sh

# 3. 시뮬레이터 실행
cd ..
./gradlew :simulator:bootRun
```

### Docker 환경
```bash
# Docker Compose에 simulator 추가 후
docker-compose up -d simulator
```

---

## 확인 방법

### 로그 확인
```
INFO  Starting traffic simulation with 100 users, delay=1000ms
INFO  Total events sent: 1000, offset: 999
INFO  Total events sent: 2000, offset: 1999
...
```

### Kafka 토픽 확인
```bash
# 토픽에 쌓인 메시지 개수 확인
docker exec rep-kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group behavior-consumer-group
```

---

## 자주 하는 질문

### Q1: 유저 수를 늘리면 어떻게 되나요?
```
A: 메모리만 충분하면 수만 명도 가능합니다.
   Virtual Thread 덕분에 유저 1명당 메모리 사용량이 매우 적어요.

   100명: 약 50MB
   1,000명: 약 100MB
   10,000명: 약 300MB
```

### Q2: 시뮬레이터를 멈추고 싶어요
```
A: Ctrl+C를 누르면 정상 종료됩니다.
   SimulatorLifecycleManager가 깔끔하게 정리해줘요.
```

### Q3: 특정 행동만 많이 발생시키고 싶어요
```
A: UserSession.kt의 ACTION_WEIGHTS를 수정하세요.

   예: 구매를 50%로 늘리고 싶다면
   val ACTION_WEIGHTS = mapOf(
       ActionType.PURCHASE to 50,  // 50%로 변경
       ActionType.VIEW to 30,
       ...
   )
```

---

## REST API (프론트엔드 연동)

시뮬레이터는 REST API를 통해 프론트엔드에서 제어할 수 있습니다.

### 기본 정보

| 항목 | 값 |
|------|-----|
| Base URL | `/api/v1/simulator` |
| 포트 | 8084 |

### 엔드포인트

#### GET /api/v1/simulator/status

시뮬레이터 상태를 조회합니다.

**요청**
```bash
curl http://localhost:8084/api/v1/simulator/status
```

**응답**
```json
{
  "isRunning": true,
  "totalEventsSent": 15234,
  "userCount": 100,
  "delayMillis": 1000
}
```

#### POST /api/v1/simulator/start

시뮬레이터를 시작합니다.

**파라미터**
| 파라미터 | 타입 | 기본값 | 설명 |
|----------|------|--------|------|
| userCount | Int | 100 | 시뮬레이션할 가상 유저 수 |
| delayMillis | Long | 1000 | 행동 간 지연 시간 (밀리초) |

**요청**
```bash
curl -X POST "http://localhost:8084/api/v1/simulator/start?userCount=50&delayMillis=500"
```

**응답**
```json
{
  "isRunning": true,
  "totalEventsSent": 0,
  "userCount": 100,
  "delayMillis": 1000
}
```

#### POST /api/v1/simulator/stop

시뮬레이터를 정지합니다.

**요청**
```bash
curl -X POST http://localhost:8084/api/v1/simulator/stop
```

**응답**
```json
{
  "isRunning": false,
  "totalEventsSent": 15234,
  "userCount": 100,
  "delayMillis": 1000
}
```

### SimulatorController.kt

```kotlin
@RestController
@RequestMapping("/api/v1/simulator")
class SimulatorController(
    private val trafficSimulator: TrafficSimulator
) {
    @GetMapping("/status")
    fun getStatus(): ResponseEntity<TrafficSimulator.SimulationStatus>

    @PostMapping("/start")
    fun start(
        @RequestParam(defaultValue = "100") userCount: Int,
        @RequestParam(defaultValue = "1000") delayMillis: Long
    ): ResponseEntity<TrafficSimulator.SimulationStatus>

    @PostMapping("/stop")
    fun stop(): ResponseEntity<TrafficSimulator.SimulationStatus>
}
```

### SimulationStatus 응답 형식

```kotlin
data class SimulationStatus(
    val isRunning: Boolean,      // 시뮬레이션 실행 중 여부
    val totalEventsSent: Long,   // 총 전송된 이벤트 수
    val userCount: Int,          // 설정된 유저 수
    val delayMillis: Long        // 설정된 지연 시간
)
```

---

## CORS 설정

프론트엔드에서 API를 호출할 수 있도록 CORS가 설정되어 있습니다.

### WebConfig.kt

```kotlin
@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(
                "http://localhost:5173",  // Vite 기본 포트
                "http://localhost:3001",  // 대체 포트
                "http://localhost:3000",  // 대체 포트
                "http://frontend:80"      // Docker
            )
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600)
    }
}
```

### 허용된 오리진

| 오리진 | 용도 |
|--------|------|
| `http://localhost:5173` | Vite 기본 포트 (Vite 4+) |
| `http://localhost:3001` | 대체 포트 |
| `http://localhost:3000` | 대체 포트 |
| `http://frontend:80` | Docker 컨테이너 간 통신 |

---

## 핵심 요약

| 항목 | 내용 |
|------|------|
| 역할 | 가짜 유저 행동 데이터 생성 |
| 기본 유저 수 | 100명 |
| 행동 간격 | 1~2초 |
| 출력 | Kafka 토픽 `user.action.v1` |
| 핵심 기술 | Virtual Thread (동시성), Avro (직렬화) |
| REST API | `/api/v1/simulator` (상태조회, 시작, 정지) |
| 다음 단계 | behavior-consumer가 이 데이터를 소비 |
