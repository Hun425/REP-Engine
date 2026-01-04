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
    │   │   └── KafkaProducerConfig.kt   # Kafka 연결 설정
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
        val CATEGORIES = listOf("ELECTRONICS", "FASHION", "HOME", "BEAUTY", "SPORTS", "FOOD", "BOOKS")
        val ACTION_WEIGHTS = mapOf(
            "VIEW" to 45,          // 45% 확률로 그냥 봄
            "CLICK" to 25,         // 25% 확률로 클릭
            "SEARCH" to 10,        // 10% 확률로 검색
            "PURCHASE" to 8,       // 8% 확률로 구매
            "ADD_TO_CART" to 7,    // 7% 확률로 장바구니
            "WISHLIST" to 5        // 5% 확률로 찜
        )
    }

    fun nextAction(): UserActionEvent {
        // 1. 어떤 카테고리에서 활동할지 랜덤 선택
        val category = CATEGORIES.random()

        // 2. 어떤 상품을 볼지 랜덤 선택
        val productId = "${category}-${Random.nextInt(productCountPerCategory)}"

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
PURCHASE: 8번 (구매)
ADD_TO_CART: 7번 (장바구니)
WISHLIST: 5번 (찜)
```

이건 실제 쇼핑몰 데이터를 참고해서 만든 비율이에요.
보통 보기만 하고 구매까지 가는 사람은 적으니까요!

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

---

### 5. KafkaProducerConfig.kt (Kafka 설정)

**역할**: Kafka에 메시지를 보내는 방법 설정

```kotlin
@Configuration
class KafkaProducerConfig {

    @Bean
    fun producerFactory(): ProducerFactory<String, UserActionEvent> {
        val configProps = mapOf(
            // Kafka 서버 주소
            BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",

            // 키 직렬화: 문자열 그대로
            KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,

            // 값 직렬화: Avro 형식으로
            VALUE_SERIALIZER_CLASS_CONFIG to KafkaAvroSerializer::class.java,

            // 안정성 설정
            ACKS_CONFIG to "all",              // 모든 브로커가 받을 때까지 대기
            ENABLE_IDEMPOTENCE_CONFIG to true, // 중복 전송 방지
            RETRIES_CONFIG to 3,               // 실패 시 3번 재시도

            // 성능 설정
            LINGER_MS_CONFIG to 5,             // 5ms 모아서 한번에 전송
            BATCH_SIZE_CONFIG to 16384,        // 16KB씩 모아서 전송

            // Schema Registry 주소
            SCHEMA_REGISTRY_URL_CONFIG to "http://localhost:8081"
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
| `linger.ms=5` | 5ms 대기 | 모아서 보내기 (효율!) |
| `batch.size=16384` | 16KB | 한번에 보낼 양 |

---

### 6. application.yml (설정 파일)

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092  # Kafka 서버 주소
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
      acks: all
      properties:
        schema.registry.url: http://localhost:8081

simulator:
  user-count: 100          # 가짜 유저 수
  delay-millis: 1000       # 1초 간격
  topic: user.action.v1    # 보낼 토픽
  enabled: true            # 시뮬레이터 ON
```

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
       "PURCHASE" to 50,  // 50%로 변경
       "VIEW" to 30,
       ...
   )
```

---

## 핵심 요약

| 항목 | 내용 |
|------|------|
| 역할 | 가짜 유저 행동 데이터 생성 |
| 기본 유저 수 | 100명 |
| 행동 간격 | 1~2초 |
| 출력 | Kafka 토픽 `user.action.v1` |
| 핵심 기술 | Virtual Thread (동시성), Avro (직렬화) |
| 다음 단계 | behavior-consumer가 이 데이터를 소비 |
