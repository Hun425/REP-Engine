# Phase 5: 운영 모니터링 및 테스트 (Observability & Testing)

본 문서는 REP-Engine의 안정적인 운영을 위한 모니터링, 로깅, 알림 체계와 테스트 전략을 정의합니다.

## 1. 관측성 (Observability) 개요

### 1.1 Three Pillars of Observability

| Pillar | 도구 | 용도 |
|--------|------|------|
| **Metrics** | Prometheus + Grafana | 수치 기반 시스템 상태 모니터링 |
| **Logs** | Loki + Grafana | 이벤트 기반 디버깅 및 감사 |
| **Traces** | Jaeger / Zipkin | 분산 시스템 요청 추적 |

### 1.2 모니터링 스택 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            Observability Stack                                   │
└─────────────────────────────────────────────────────────────────────────────────┘

  [Applications]
       │
       ├─── Metrics (/actuator/prometheus) ───▶ Prometheus ───▶ Grafana
       │
       ├─── Logs (JSON) ───▶ Promtail ───▶ Loki ───▶ Grafana
       │
       └─── Traces (OpenTelemetry) ───▶ Jaeger ───▶ Grafana

  [Infrastructure]
       │
       ├─── Kafka ───▶ kafka_exporter ───▶ Prometheus
       │
       ├─── Elasticsearch ───▶ elasticsearch_exporter ───▶ Prometheus
       │
       └─── Redis ───▶ redis_exporter ───▶ Prometheus
```


## 2. Prometheus 설정

### 2.1 prometheus.yml

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']

rule_files:
  - '/etc/prometheus/rules/*.yml'

scrape_configs:
  # Spring Boot Applications
  - job_name: 'rep-engine'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets:
          - 'simulator:8080'
          - 'behavior-consumer:8080'
          - 'recommendation-api:8080'
          - 'notification-worker:8080'

  # Kafka
  - job_name: 'kafka'
    static_configs:
      - targets: ['kafka-exporter:9308']

  # Elasticsearch
  - job_name: 'elasticsearch'
    static_configs:
      - targets: ['elasticsearch-exporter:9114']

  # Redis
  - job_name: 'redis'
    static_configs:
      - targets: ['redis-exporter:9121']
```

### 2.2 Spring Boot Actuator 설정

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      application: ${spring.application.name}
    export:
      prometheus:
        enabled: true
```


## 3. 핵심 메트릭 및 대시보드

### 3.1 비즈니스 메트릭

| 메트릭 | 설명 | PromQL |
|--------|------|--------|
| 이벤트 처리량 | 초당 처리된 유저 행동 이벤트 | `rate(kafka_consumer_processed_total[1m])` |
| 추천 API 레이턴시 | P99 응답 시간 | `histogram_quantile(0.99, rate(recommendation_latency_seconds_bucket[5m]))` |
| 추천 전략 비율 | KNN vs Cold Start 비율 | `sum by(strategy) (rate(recommendations_total[5m]))` |
| 알림 발송 수 | 분당 발송된 알림 | `rate(notifications_sent_total[1m]) * 60` |

### 3.2 시스템 메트릭

| 메트릭 | 설명 | 경고 임계값 |
|--------|------|------------|
| Kafka Consumer Lag | 처리 지연 메시지 수 | > 10,000 |
| ES Indexing Rate | 초당 인덱싱 문서 수 | < 100 (목표 대비 저조) |
| Redis Memory | 메모리 사용률 | > 80% |
| JVM Heap Usage | 힙 메모리 사용률 | > 85% |
| GC Pause Time | GC 정지 시간 | > 100ms |

### 3.3 Grafana 대시보드 JSON (요약)

```json
{
  "title": "REP-Engine Overview",
  "panels": [
    {
      "title": "Event Processing Rate",
      "type": "graph",
      "targets": [{
        "expr": "sum(rate(kafka_consumer_processed_total[1m]))",
        "legendFormat": "events/sec"
      }]
    },
    {
      "title": "Recommendation API Latency (P99)",
      "type": "gauge",
      "targets": [{
        "expr": "histogram_quantile(0.99, sum(rate(recommendation_latency_seconds_bucket[5m])) by (le))",
        "legendFormat": "p99"
      }],
      "thresholds": [
        {"value": 0, "color": "green"},
        {"value": 0.05, "color": "yellow"},
        {"value": 0.1, "color": "red"}
      ]
    },
    {
      "title": "Kafka Consumer Lag",
      "type": "graph",
      "targets": [{
        "expr": "sum(kafka_consumer_group_lag) by (topic)",
        "legendFormat": "{{topic}}"
      }]
    }
  ]
}
```


## 4. 알림 규칙 (Alerting Rules)

### 4.1 alert_rules.yml

```yaml
groups:
  - name: rep-engine-alerts
    rules:
      # 높은 Consumer Lag
      - alert: HighKafkaConsumerLag
        expr: sum(kafka_consumer_group_lag{group="behavior-consumer-group"}) > 10000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Kafka Consumer Lag이 높습니다"
          description: "behavior-consumer-group의 lag이 {{ $value }}입니다. 처리 지연이 발생할 수 있습니다."

      # 추천 API 지연
      - alert: HighRecommendationLatency
        expr: histogram_quantile(0.99, sum(rate(recommendation_latency_seconds_bucket[5m])) by (le)) > 0.1
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "추천 API P99 레이턴시가 100ms를 초과합니다"
          description: "현재 P99: {{ $value | humanizeDuration }}"

      # ES 클러스터 상태
      - alert: ElasticsearchClusterRed
        expr: elasticsearch_cluster_health_status{color="red"} == 1
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Elasticsearch 클러스터가 RED 상태입니다"
          description: "즉시 확인이 필요합니다."

      # Redis 메모리
      - alert: RedisHighMemoryUsage
        expr: redis_memory_used_bytes / redis_memory_max_bytes > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Redis 메모리 사용률이 80%를 초과합니다"

      # DLQ 메시지 발생
      - alert: DLQMessagesDetected
        expr: increase(kafka_topic_partition_current_offset{topic=~".*\\.dlq"}[5m]) > 0
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "DLQ에 메시지가 적재되고 있습니다"
          description: "{{ $labels.topic }} 토픽을 확인하세요."

      # 애플리케이션 다운
      - alert: ApplicationDown
        expr: up{job="rep-engine"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "{{ $labels.instance }} 애플리케이션이 다운되었습니다"
```

### 4.2 Alertmanager 설정

```yaml
# alertmanager.yml
global:
  slack_api_url: 'https://hooks.slack.com/services/xxx/xxx/xxx'

route:
  group_by: ['alertname', 'severity']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  receiver: 'slack-notifications'

  routes:
    - match:
        severity: critical
      receiver: 'slack-critical'
      repeat_interval: 1h

receivers:
  - name: 'slack-notifications'
    slack_configs:
      - channel: '#rep-engine-alerts'
        title: '{{ .GroupLabels.alertname }}'
        text: '{{ range .Alerts }}{{ .Annotations.description }}{{ end }}'

  - name: 'slack-critical'
    slack_configs:
      - channel: '#rep-engine-critical'
        title: '[CRITICAL] {{ .GroupLabels.alertname }}'
        text: '{{ range .Alerts }}{{ .Annotations.description }}{{ end }}'
```


## 5. 로깅 (Logging)

### 5.1 구조화된 로그 포맷 (JSON)

```kotlin
// logback-spring.xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>userId</includeMdcKeyName>
            <includeMdcKeyName>requestId</includeMdcKeyName>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

### 5.2 로그 출력 예시

```json
{
  "@timestamp": "2026-01-03T10:30:00.123Z",
  "level": "INFO",
  "logger": "c.r.s.RecommendationService",
  "message": "Recommendation generated",
  "traceId": "abc123",
  "userId": "U12345",
  "strategy": "knn",
  "latencyMs": 45,
  "resultCount": 10
}
```

### 5.3 MDC를 활용한 컨텍스트 전파

```kotlin
@Component
class LoggingFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val requestId = exchange.request.headers.getFirst("X-Request-ID")
            ?: UUID.randomUUID().toString()

        return chain.filter(exchange)
            .contextWrite { ctx ->
                ctx.put("requestId", requestId)
            }
            .doOnEach { signal ->
                if (!signal.isOnComplete) {
                    MDC.put("requestId", requestId)
                }
            }
            .doFinally {
                MDC.clear()
            }
    }
}
```


## 6. 분산 추적 (Distributed Tracing)

### 6.1 OpenTelemetry 설정

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-jaeger")
    implementation("io.opentelemetry.instrumentation:opentelemetry-kafka-clients-2.6")
}
```

```yaml
# application.yml
otel:
  exporter:
    jaeger:
      endpoint: http://jaeger:14250
  service:
    name: ${spring.application.name}
```

### 6.2 수동 Span 생성

```kotlin
@Component
class RecommendationService(
    private val tracer: Tracer
) {
    suspend fun getRecommendations(userId: String): List<Product> {
        val span = tracer.spanBuilder("getRecommendations")
            .setAttribute("userId", userId)
            .startSpan()

        return try {
            span.makeCurrent().use {
                // 추천 로직
                val vector = getPreferenceVector(userId)  // 자식 span 자동 생성
                searchProducts(vector)                     // 자식 span 자동 생성
            }
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            span.end()
        }
    }
}
```


## 7. 테스트 전략

### 7.1 테스트 피라미드

```
                    ┌───────────┐
                    │   E2E     │  ← Docker Compose 전체 시스템
                    │  (10%)    │
                ┌───┴───────────┴───┐
                │   Integration     │  ← Testcontainers
                │      (20%)        │
            ┌───┴───────────────────┴───┐
            │         Unit              │  ← MockK, JUnit 5
            │         (70%)             │
            └───────────────────────────┘
```

### 7.2 Unit Test 예시

```kotlin
class PreferenceVectorCalculatorTest {
    private val calculator = PreferenceVectorCalculator()

    @Test
    fun `새로운 유저는 첫 상품 벡터를 그대로 사용한다`() {
        val productVector = floatArrayOf(0.1f, 0.2f, 0.3f)

        val result = calculator.update(
            currentPreference = null,
            newProductVector = productVector,
            actionType = ActionType.CLICK
        )

        assertThat(result).isEqualTo(productVector)
    }

    @Test
    fun `기존 취향이 있으면 EMA로 업데이트한다`() {
        val current = floatArrayOf(1.0f, 0.0f, 0.0f)
        val newProduct = floatArrayOf(0.0f, 1.0f, 0.0f)

        val result = calculator.update(
            currentPreference = current,
            newProductVector = newProduct,
            actionType = ActionType.CLICK  // alpha = 0.3
        )

        // Expected: [0.7, 0.3, 0.0] normalized
        assertThat(result[0]).isCloseTo(0.92f, Offset.offset(0.01f))
        assertThat(result[1]).isCloseTo(0.39f, Offset.offset(0.01f))
    }
}
```

### 7.3 Integration Test (Testcontainers)

```kotlin
@SpringBootTest
@Testcontainers
class BehaviorConsumerIntegrationTest {

    companion object {
        @Container
        val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))

        @Container
        val elasticsearch = ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.11.0")
        )

        @Container
        val redis = GenericContainer(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
            registry.add("spring.elasticsearch.uris") { elasticsearch.httpHostAddress }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, UserActionEvent>

    @Autowired
    lateinit var esClient: ElasticsearchClient

    @Test
    fun `유저 행동 이벤트가 ES에 인덱싱된다`() {
        // Given
        val event = UserActionEvent(
            traceId = UUID.randomUUID().toString(),
            userId = "U123",
            productId = "P456",
            category = "ELECTRONICS",
            actionType = ActionType.CLICK,
            timestamp = System.currentTimeMillis()
        )

        // When
        kafkaTemplate.send("user.action.v1", event.userId, event).get()

        // Then (ES refresh 대기)
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val response = esClient.get({ g ->
                g.index("user_behavior_index").id(event.traceId)
            }, UserActionEvent::class.java)

            assertThat(response.found()).isTrue()
            assertThat(response.source()?.userId).isEqualTo("U123")
        }
    }
}
```

### 7.4 E2E Test (Docker Compose)

```kotlin
@SpringBootTest
@ActiveProfiles("e2e")
class RecommendationE2ETest {

    @Autowired
    lateinit var webClient: WebTestClient

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, UserActionEvent>

    @Test
    fun `유저가 전자기기를 클릭하면 전자기기가 추천된다`() {
        val userId = "TEST-USER-${UUID.randomUUID()}"

        // 1. 전자기기 카테고리 상품 10번 클릭
        repeat(10) { i ->
            kafkaTemplate.send("user.action.v1", userId, UserActionEvent(
                traceId = UUID.randomUUID().toString(),
                userId = userId,
                productId = "ELECTRONICS-$i",
                category = "ELECTRONICS",
                actionType = ActionType.CLICK,
                timestamp = System.currentTimeMillis()
            )).get()
        }

        // 2. 취향 벡터 업데이트 대기
        Thread.sleep(5000)

        // 3. 추천 API 호출
        val response = webClient.get()
            .uri("/api/v1/recommendations/$userId")
            .exchange()
            .expectStatus().isOk
            .expectBody<RecommendationResponse>()
            .returnResult()
            .responseBody!!

        // 4. 전자기기 카테고리 상품이 70% 이상 추천되어야 함
        val electronicsCount = response.recommendations.count { it.category == "ELECTRONICS" }
        assertThat(electronicsCount.toDouble() / response.recommendations.size)
            .isGreaterThanOrEqualTo(0.7)
    }
}
```


## 8. 부하 테스트 (Performance Testing)

### 8.1 k6 스크립트

```javascript
// load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const recommendationLatency = new Trend('recommendation_latency');

export const options = {
    stages: [
        { duration: '1m', target: 100 },   // Ramp-up
        { duration: '5m', target: 100 },   // Steady state
        { duration: '1m', target: 0 },     // Ramp-down
    ],
    thresholds: {
        http_req_duration: ['p(99)<100'],  // P99 < 100ms
        errors: ['rate<0.01'],              // 에러율 < 1%
    },
};

export default function () {
    const userId = `USER-${Math.floor(Math.random() * 100000)}`;

    const start = Date.now();
    const res = http.get(`http://localhost:8080/api/v1/recommendations/${userId}`);
    const duration = Date.now() - start;

    recommendationLatency.add(duration);

    check(res, {
        'status is 200': (r) => r.status === 200,
        'has recommendations': (r) => JSON.parse(r.body).recommendations.length > 0,
    });

    errorRate.add(res.status !== 200);

    sleep(1);
}
```

### 8.2 실행 및 결과 분석

```bash
# 부하 테스트 실행
k6 run load-test.js

# 결과 예시
✓ status is 200
✓ has recommendations

checks.........................: 100.00% ✓ 30000 ✗ 0
http_req_duration..............: avg=45ms min=12ms med=42ms max=210ms p(90)=78ms p(95)=89ms p(99)=98ms
recommendation_latency.........: avg=45.2 min=12 med=42 max=210 p(90)=78 p(95)=89 p(99)=98
errors.........................: 0.00%   ✓ 0     ✗ 30000
```


## 9. Phase 5 성공 기준 (Exit Criteria)

| 기준 | 측정 방법 | 목표 |
|-----|----------|------|
| 메트릭 수집 | Prometheus targets UP | 100% |
| 대시보드 | 주요 패널 데이터 표시 | 모든 패널 정상 |
| 알림 동작 | 테스트 알림 발송 | Slack 수신 확인 |
| 로그 조회 | Loki에서 traceId 검색 | 정상 조회 |
| 분산 추적 | Jaeger에서 요청 추적 | 전체 경로 표시 |
| 테스트 커버리지 | Unit + Integration | 70% 이상 |
| 부하 테스트 | P99 레이턴시 | 100ms 이내 |


## 10. 관련 문서

- [Phase 4: 실시간 알림 시스템](./phase%204.md)
- [Infrastructure: 인프라 구성](./infrastructure.md)
- [마스터 설계서](./마스터%20설계서.md)