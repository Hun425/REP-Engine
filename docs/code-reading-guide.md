# REP-Engine 코드 리딩 가이드

이 문서는 REP-Engine 코드베이스를 **데이터 흐름 순서**로 읽기 위한 가이드다.
아키텍처 전체를 이해한 뒤, 이벤트가 생성되어 최종 사용자에게 도달하는 흐름을 따라간다.

---

## 읽기 순서 요약

```
[0단계] 설계 문서 & 인프라          ← 전체 그림 파악
[1단계] common-avro / common-model  ← 메시지 & 데이터 모델 정의
[2단계] simulator                   ← 이벤트 생성 (Producer)
[3단계] behavior-consumer           ← 이벤트 소비 & 인덱싱 (Consumer)
[4단계] recommendation-api          ← 추천 검색 API (Query)
[5단계] notification-service        ← 변동 감지 & 알림 (Reactive)
[6단계] frontend                    ← 사용자 대시보드 (UI)
[7단계] docker / monitoring         ← 인프라 & 관측성
```

---

## 0단계: 설계 문서 & 인프라 이해

코드를 읽기 전에 **왜 이런 구조인지** 먼저 파악한다.

### 읽기 순서

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 1 | `docs/마스터 설계서.md` | 전체 아키텍처, 기술 선택 이유, 모듈 간 관계 |
| 2 | `docs/infrastructure.md` | Docker Compose 구성, Kafka 토픽, ES 인덱스 정의 |
| 3 | `docs/adr-001-concurrency-strategy.md` | Coroutines + Virtual Threads 전략 |
| 4 | `docs/adr-002-schema-registry.md` | Avro 직렬화 & Schema Registry 운영 방식 |
| 5 | `docs/adr-003-embedding-model.md` | multilingual-e5-base 선택 근거, 768차원 벡터 |
| 6 | `docs/adr-004-vector-storage.md` | ES + Redis 하이브리드 벡터 저장 전략 |

### 핵심 포인트
- Kafka 토픽 3개: `user.action.v1`, `product.inventory.v1`, `notification.push.v1`
- 벡터 차원: 768 (multilingual-e5-base)
- 유저 취향 벡터: Redis(캐시) + ES(백업) 이중 저장
- 동시성: Coroutines(IO-bound) + Virtual Threads(CPU-bound)

---

## 1단계: 공유 모듈 — 메시지 스키마 & 데이터 모델

모든 서비스가 공유하는 **계약(Contract)** 을 먼저 이해한다.

### 1-1. common-avro (Kafka 메시지 스키마)

> Phase 문서: `docs/phase 1.md` (Avro 스키마 정의 부분)

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 1 | `common-avro/build.gradle.kts` | Avro 코드 생성 플러그인 설정 확인 |
| 2 | `common-avro/src/main/avro/user-action-event.avsc` | 유저 행동 이벤트 스키마 (VIEW, CLICK, SEARCH, PURCHASE, ADD_TO_CART, WISHLIST) |
| 3 | `common-avro/src/main/avro/product-inventory-event.avsc` | 상품 재고/가격 변동 스키마 |
| 4 | `common-avro/src/main/avro/notification-event.avsc` | 알림 이벤트 스키마 (PRICE_DROP, BACK_IN_STOCK, RECOMMENDATION) |

**이해 체크:**
- [ ] 각 스키마의 필드와 enum 타입을 파악했는가?
- [ ] 어떤 토픽에 어떤 스키마가 매핑되는지 알겠는가?

### 1-2. common-model (ES 문서 & Redis 데이터 모델)

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 1 | `common-model/build.gradle.kts` | Jackson 의존성 확인 |
| 2 | `common-model/src/main/kotlin/com/rep/model/ProductDocument.kt` | ES에 저장되는 상품 문서 구조 (벡터 포함) |
| 3 | `common-model/src/main/kotlin/com/rep/model/UserPreferenceData.kt` | Redis에 캐시되는 유저 취향 데이터 |
| 4 | `common-model/src/main/kotlin/com/rep/model/UserPreferenceDocument.kt` | ES에 백업되는 유저 취향 문서 |
| 5 | `common-model/src/main/kotlin/com/rep/model/NotificationHistory.kt` | 알림 이력 모델 |

**이해 체크:**
- [ ] ProductDocument에 벡터 필드(768차원)가 어떻게 정의되어 있는가?
- [ ] UserPreferenceData와 UserPreferenceDocument의 차이는?

---

## 2단계: simulator — 이벤트 생성 (데이터 흐름의 시작점)

> Phase 문서: `docs/phase 1.md`

시뮬레이터가 가상 유저 행동을 생성해서 Kafka로 보내는 과정을 추적한다.

### 읽기 순서

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 1 | `simulator/src/main/resources/application.yml` | 설정값 (포트, Kafka 브로커, 시뮬레이터 파라미터) |
| 2 | `simulator/src/main/kotlin/com/rep/simulator/config/SimulatorProperties.kt` | 설정 바인딩 클래스 |
| 3 | `simulator/src/main/kotlin/com/rep/simulator/domain/UserSession.kt` | 유저 세션 도메인 모델 |
| 4 | `simulator/src/main/kotlin/com/rep/simulator/config/KafkaProducerConfig.kt` | Kafka Producer 설정 (Avro Serializer) |
| 5 | `simulator/src/main/kotlin/com/rep/simulator/service/TrafficSimulator.kt` | **핵심.** 트래픽 생성 로직 — 유저 행동 패턴 시뮬레이션 |
| 6 | `simulator/src/main/kotlin/com/rep/simulator/controller/SimulatorController.kt` | REST API (시뮬레이터 시작/중지) |
| 7 | `simulator/src/main/kotlin/com/rep/simulator/SimulatorApplication.kt` | 진입점 & 라이프사이클 관리 |

### 데이터 흐름
```
TrafficSimulator
  → UserActionEvent(Avro) 생성
    → KafkaProducer.send("user.action.v1")
      → Kafka 브로커 (12 파티션)
```

**이해 체크:**
- [ ] 어떤 종류의 유저 행동(ActionType)이 생성되는가?
- [ ] 각 이벤트에 포함되는 데이터 필드는?
- [ ] 시뮬레이터 시작/중지는 어떻게 제어되는가?

---

## 3단계: behavior-consumer — 이벤트 소비 & ES 인덱싱

> Phase 문서: `docs/phase 2.md`

Kafka에서 이벤트를 소비해서 ES에 벌크 인덱싱하고, 유저 취향 벡터를 갱신하는 핵심 파이프라인이다.

### 읽기 순서

**3-1. 설정 파악**

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 1 | `behavior-consumer/src/main/resources/application.yml` | 전체 설정값 (Kafka, ES, Redis, Embedding) |
| 2 | `behavior-consumer/src/main/kotlin/com/rep/consumer/config/ConsumerProperties.kt` | Consumer 설정 바인딩 |
| 3 | `behavior-consumer/src/main/kotlin/com/rep/consumer/config/KafkaConsumerConfig.kt` | Kafka Consumer 설정 (배치 리스너, 동시성) |
| 4 | `behavior-consumer/src/main/kotlin/com/rep/consumer/config/ElasticsearchConfig.kt` | ES 클라이언트 설정 |
| 5 | `behavior-consumer/src/main/kotlin/com/rep/consumer/config/RedisConfig.kt` | Redis Reactive 설정 |
| 6 | `behavior-consumer/src/main/kotlin/com/rep/consumer/config/DispatcherConfig.kt` | Coroutine Dispatcher 설정 |

**3-2. 이벤트 소비 & 처리 흐름** (핵심)

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 7 | `behavior-consumer/src/main/kotlin/com/rep/consumer/listener/BehaviorEventListener.kt` | **핵심.** Kafka 배치 리스너 — 이벤트 수신 진입점 |
| 8 | `behavior-consumer/src/main/kotlin/com/rep/consumer/service/BulkIndexer.kt` | ES Bulk Indexing 로직 (500건 단위) |
| 9 | `behavior-consumer/src/main/kotlin/com/rep/consumer/repository/ProductVectorRepository.kt` | 상품 벡터 ES 조회 |
| 10 | `behavior-consumer/src/main/kotlin/com/rep/consumer/service/PreferenceVectorCalculator.kt` | **핵심.** EMA 기반 유저 취향 벡터 계산 |
| 11 | `behavior-consumer/src/main/kotlin/com/rep/consumer/service/PreferenceUpdater.kt` | 유저 취향 Redis 캐시 + ES 백업 갱신 |
| 12 | `behavior-consumer/src/main/kotlin/com/rep/consumer/repository/UserPreferenceRepository.kt` | 유저 취향 Redis CRUD |
| 13 | `behavior-consumer/src/main/kotlin/com/rep/consumer/client/EmbeddingClient.kt` | Python Embedding Service 호출 클라이언트 |
| 14 | `behavior-consumer/src/main/kotlin/com/rep/consumer/service/DlqProducer.kt` | Dead Letter Queue 처리 |

### 데이터 흐름
```
Kafka("user.action.v1")
  → BehaviorEventListener.onMessage() [배치 수신]
    → BulkIndexer.index() [ES에 행동 로그 저장]
    → PreferenceVectorCalculator.calculate() [EMA 가중 평균]
      → ProductVectorRepository.getVector() [상품 벡터 조회]
      → EMA 가중치 적용 (PURCHASE=0.5, CLICK=0.3, VIEW=0.1 등)
    → PreferenceUpdater.update()
      → UserPreferenceRepository [Redis 캐시 저장, 24h TTL]
      → ES 백업 저장
```

**이해 체크:**
- [ ] 배치 리스너가 어떻게 500건 단위로 처리하는가?
- [ ] EMA(지수 이동 평균) 가중치가 행동 타입별로 어떻게 다른가?
- [ ] 유저 취향 벡터가 Redis와 ES에 어떻게 이중 저장되는가?
- [ ] DLQ 처리 흐름은?

---

## 4단계: recommendation-api — 추천 검색 API

> Phase 문서: `docs/phase 3.md`

유저 취향 벡터로 ES KNN 검색을 수행해서 추천 상품을 반환하는 API다.

### 읽기 순서

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 1 | `recommendation-api/src/main/resources/application.yml` | 설정값 (KNN k=10, cosine similarity) |
| 2 | `recommendation-api/src/main/kotlin/com/rep/recommendation/config/RecommendationProperties.kt` | 추천 설정 바인딩 |
| 3 | `recommendation-api/src/main/kotlin/com/rep/recommendation/config/AsyncConfig.kt` | Virtual Threads 설정 |
| 4 | `recommendation-api/src/main/kotlin/com/rep/recommendation/model/RecommendationModels.kt` | 요청/응답 DTO |
| 5 | `recommendation-api/src/main/kotlin/com/rep/recommendation/controller/RecommendationController.kt` | REST 엔드포인트 정의 |
| 6 | `recommendation-api/src/main/kotlin/com/rep/recommendation/service/RecommendationService.kt` | **핵심.** KNN 검색 로직 |
| 7 | `recommendation-api/src/main/kotlin/com/rep/recommendation/repository/UserPreferenceRepository.kt` | Redis에서 유저 취향 벡터 조회 |
| 8 | `recommendation-api/src/main/kotlin/com/rep/recommendation/repository/UserBehaviorRepository.kt` | ES에서 유저 행동 이력 조회 |
| 9 | `recommendation-api/src/main/kotlin/com/rep/recommendation/service/PopularProductsCache.kt` | 인기 상품 폴백 캐시 |

### 데이터 흐름
```
Client → GET /api/recommendations/{userId}
  → RecommendationController
    → RecommendationService.recommend()
      → UserPreferenceRepository.get() [Redis에서 취향 벡터 조회]
      → ES KNN Search (cosine, k=10, num_candidates=100)
      → 결과 필터링 (이미 본 상품 제외)
      → 취향 벡터 없으면 → PopularProductsCache (폴백)
    → RecommendationResponse 반환
```

**이해 체크:**
- [ ] KNN 검색 쿼리가 어떻게 구성되는가? (k, num_candidates, similarity)
- [ ] 유저 취향 벡터가 없을 때 폴백 전략은?
- [ ] Virtual Threads가 어디에 적용되는가?

---

## 5단계: notification-service — 가격/재고 변동 감지 & 알림

> Phase 문서: `docs/phase 4.md`

상품 가격 하락이나 재입고를 감지해서 관심 유저에게 알림을 보내는 서비스다.

### 읽기 순서

**5-1. 설정 파악**

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 1 | `notification-service/src/main/resources/application.yml` | 설정값 (가격 하락 임계값, 일일 알림 제한 등) |
| 2 | `notification-service/src/main/kotlin/com/rep/notification/config/NotificationProperties.kt` | 알림 설정 바인딩 |
| 3 | `notification-service/src/main/kotlin/com/rep/notification/config/KafkaConsumerConfig.kt` | 재고 이벤트 Consumer 설정 |
| 4 | `notification-service/src/main/kotlin/com/rep/notification/config/ShedLockConfig.kt` | 분산 락 설정 (스케줄러 중복 방지) |

**5-2. 재고/가격 변동 → 알림 흐름** (핵심)

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 5 | `notification-service/src/main/kotlin/com/rep/notification/consumer/InventoryEventConsumer.kt` | **핵심.** Kafka 재고 이벤트 소비 진입점 |
| 6 | `notification-service/src/main/kotlin/com/rep/notification/service/EventDetector.kt` | 가격 하락(10%+) / 재입고 감지 로직 |
| 7 | `notification-service/src/main/kotlin/com/rep/notification/service/TargetResolver.kt` | 관심 유저 탐색 (해당 상품 조회/구매 이력 기반) |
| 8 | `notification-service/src/main/kotlin/com/rep/notification/service/NotificationRateLimiter.kt` | 알림 빈도 제한 (일일 한도, 중복 방지) |
| 9 | `notification-service/src/main/kotlin/com/rep/notification/service/NotificationProducer.kt` | 알림 이벤트 Kafka 발행 |
| 10 | `notification-service/src/main/kotlin/com/rep/notification/service/NotificationHistoryService.kt` | 알림 이력 저장 |
| 11 | `notification-service/src/main/kotlin/com/rep/notification/consumer/PushSenderSimulator.kt` | 알림 발송 시뮬레이터 (실제 푸시 대신) |

**5-3. 스케줄러 (정기 추천 알림)**

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 12 | `notification-service/src/main/kotlin/com/rep/notification/scheduler/RecommendationScheduler.kt` | 매일 9시 추천 알림 스케줄링 |
| 13 | `notification-service/src/main/kotlin/com/rep/notification/repository/ActiveUserRepository.kt` | 활성 유저 조회 (최근 7일) |
| 14 | `notification-service/src/main/kotlin/com/rep/notification/client/RecommendationClient.kt` | recommendation-api 호출 클라이언트 |

### 데이터 흐름

**실시간 알림:**
```
Kafka("product.inventory.v1")
  → InventoryEventConsumer.onMessage()
    → EventDetector.detect() [가격 10%↓ 또는 재입고 감지]
    → TargetResolver.resolve() [관심 유저 탐색]
    → NotificationRateLimiter.check() [빈도 제한]
    → NotificationProducer.send("notification.push.v1")
      → PushSenderSimulator.send() [발송 시뮬레이션]
      → NotificationHistoryService.save() [이력 저장]
```

**정기 추천 알림:**
```
RecommendationScheduler (매일 09:00, ShedLock)
  → ActiveUserRepository.findActiveUsers() [최근 7일 활성 유저]
  → RecommendationClient.getRecommendations() [추천 API 호출]
  → NotificationProducer.send("notification.push.v1")
```

**이해 체크:**
- [ ] 가격 하락 감지 임계값(10%)은 어디서 설정하는가?
- [ ] Rate Limiter가 어떻게 중복 알림을 방지하는가?
- [ ] ShedLock이 왜 필요한가? (다중 인스턴스 환경)

---

## 6단계: frontend — React 대시보드

> Phase 문서: `docs/phase 6.md`

백엔드 API를 호출해서 시각화하는 프론트엔드다.

### 읽기 순서

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 1 | `frontend/package.json` | 의존성 목록 (React 19, TanStack Query, Zustand, Recharts) |
| 2 | `frontend/src/main.tsx` | 앱 진입점 (TanStack Query Provider) |
| 3 | `frontend/src/App.tsx` | 라우팅 구조 (React Router) |
| 4 | `frontend/src/api/types.ts` | TypeScript 타입 정의 |
| 5 | `frontend/src/api/client.ts` | Axios HTTP 클라이언트 (baseURL 설정) |
| 6 | `frontend/src/api/simulator.ts` | 시뮬레이터 API 호출 |
| 7 | `frontend/src/api/recommendation.ts` | 추천 API 호출 |
| 8 | `frontend/src/stores/uiStore.ts` | Zustand 상태 관리 |
| 9 | `frontend/src/components/layout/MainLayout.tsx` | 메인 레이아웃 (사이드바 + 콘텐츠) |
| 10 | `frontend/src/features/dashboard/DashboardPage.tsx` | 대시보드 페이지 |
| 11 | `frontend/src/features/simulator/SimulatorPage.tsx` | 시뮬레이터 제어 페이지 |
| 12 | `frontend/src/features/recommendations/RecommendationsPage.tsx` | 추천 결과 페이지 |
| 13 | `frontend/src/features/monitoring/MonitoringPage.tsx` | 모니터링 페이지 |

### 프론트 → 백엔드 연결
```
SimulatorPage → POST /api/simulator/start, /stop  → simulator (8084)
RecommendationsPage → GET /api/recommendations/{userId} → recommendation-api (8082)
DashboardPage → 각 서비스 상태 조회
MonitoringPage → Grafana iframe 또는 메트릭 조회
```

---

## 7단계: Docker & 모니터링 인프라

> Phase 문서: `docs/phase 5.md`, `docs/infrastructure.md`

### 읽기 순서

| 순서 | 파일 | 읽는 목적 |
|:---:|------|----------|
| 1 | `docker/docker-compose.yml` | **핵심.** 전체 인프라 구성 (서비스 간 네트워크, 포트, 환경변수) |
| 2 | `docker/init-topics.sh` | Kafka 토픽 생성 스크립트 |
| 3 | `docker/init-indices.sh` | ES 인덱스 생성 스크립트 (매핑 정의) |
| 4 | `docker/seed_products.py` | 상품 시드 데이터 생성 |
| 5 | `docker/embedding-service/embedding_service.py` | Python Embedding 서비스 (FastAPI + multilingual-e5-base) |
| 6 | `docker/prometheus/prometheus.yml` | Prometheus 수집 대상 설정 |
| 7 | `docker/prometheus/rules/alert_rules.yml` | 알림 규칙 정의 |
| 8 | `docker/grafana/provisioning/` | Grafana 대시보드 & 데이터소스 자동 프로비저닝 |
| 9 | `docker/loki/loki-config.yaml` | Loki 로그 저장소 설정 |
| 10 | `docker/promtail/promtail-config.yaml` | Promtail 로그 수집 설정 |

---

## 전체 데이터 흐름 요약

```
[Simulator] ──UserActionEvent──→ Kafka(user.action.v1)
                                       │
                                       ▼
                              [Behavior-Consumer]
                               ├─ ES Bulk Index (행동 로그)
                               ├─ EMA 벡터 계산
                               ├─ Redis 캐시 (유저 취향)
                               └─ ES 백업 (유저 취향)
                                       │
                     ┌─────────────────┘
                     ▼
            [Recommendation-API]
             ├─ Redis → 유저 취향 벡터
             ├─ ES KNN Search → 추천 상품
             └─ 인기 상품 폴백
                     │
                     ▼
              [Frontend 대시보드]


[Simulator] ──InventoryEvent──→ Kafka(product.inventory.v1)
                                       │
                                       ▼
                           [Notification-Service]
                            ├─ 가격 하락 / 재입고 감지
                            ├─ 관심 유저 탐색
                            ├─ Rate Limiting
                            └─→ Kafka(notification.push.v1)
                                       │
                                       ▼
                              [Push 발송 시뮬레이터]
```

---

## 부록: 모듈 간 포트 매핑

| 서비스 | 로컬 포트 | Docker 포트 |
|--------|----------|------------|
| Simulator | 8084 | 8084 |
| Behavior Consumer | 8081 | 8081 |
| Recommendation API | 8080 | 8082 |
| Notification Service | 8083 | 8083 |
| Frontend (dev) | 5173 | 3001 |
| Kafka | 9092 | 29092 |
| Schema Registry | 8081 | 8081 |
| Elasticsearch | 9200 | 9200 |
| Redis | 6379 | 6379 |
| Embedding Service | 8000 | 8000 |
| Prometheus | 9090 | 9090 |
| Grafana | 3000 | 3000 |
| Jaeger UI | — | 16686 |
