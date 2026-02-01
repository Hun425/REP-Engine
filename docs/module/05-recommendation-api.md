# recommendation-api 모듈 설명서

## 이 모듈이 하는 일 (한 줄 요약)

**유저에게 "당신이 좋아할 만한 상품"을 추천해주는 API 서버**

---

## 비유로 이해하기

넷플릭스에서 "당신을 위한 추천"이 뜨는 것처럼,
이 모듈은 **유저 취향을 분석해서 비슷한 상품을 찾아주는 역할**을 합니다.

```
유저: "나한테 맞는 상품 추천해줘"
API: "네 취향을 보니까 가죽 제품 좋아하시네요!
      비슷한 상품 10개 추천해드릴게요~"
```

---

## 파일 구조

```
recommendation-api/
├── build.gradle.kts
└── src/main/
    ├── kotlin/com/rep/recommendation/
    │   ├── RecommendationApiApplication.kt  # 앱 시작점
    │   ├── config/
    │   │   ├── RecommendationProperties.kt  # 설정값
    │   │   ├── ElasticsearchConfig.kt       # ES 연결
    │   │   ├── RedisConfig.kt               # Redis 연결
    │   │   └── WebConfig.kt                 # CORS 설정
    │   ├── controller/
    │   │   └── RecommendationController.kt  # API 엔드포인트
    │   ├── service/
    │   │   ├── RecommendationService.kt     # 추천 로직
    │   │   └── PopularProductsCache.kt      # 인기 상품 캐시
    │   ├── repository/
    │   │   ├── UserPreferenceRepository.kt  # 취향 조회
    │   │   └── UserBehaviorRepository.kt    # 행동 기록 조회
    │   └── model/
    │       └── RecommendationModels.kt      # 응답 모델
    └── resources/
        └── application.yml
```

---

## API 사용법

### 1. 개인화 추천 API

**요청**:
```http
GET /api/v1/recommendations/{userId}?limit=10&category=ELECTRONICS
```

**파라미터**:
| 이름 | 필수 | 기본값 | 설명 |
|------|------|--------|------|
| userId | O | - | 유저 ID |
| limit | X | 10 | 추천 개수 (최대 50) |
| category | X | null | 카테고리 필터 |
| excludeViewed | X | true | 이미 본 상품 제외 |

**응답 예시**:
```json
{
  "userId": "USER-000001",
  "recommendations": [
    {
      "productId": "ELECTRONICS-0042",
      "productName": "삼성 갤럭시 버즈3",
      "category": "ELECTRONICS",
      "price": 199000.0,
      "score": 0.95
    },
    {
      "productId": "ELECTRONICS-0087",
      "productName": "애플 에어팟 프로",
      "category": "ELECTRONICS",
      "price": 359000.0,
      "score": 0.92
    }
  ],
  "strategy": "knn",
  "latencyMs": 45
}
```

### 2. 인기 상품 API

**요청**:
```http
GET /api/v1/recommendations/popular?limit=10
```

**응답**: 위와 동일한 형식, `strategy`가 `"popularity"`

### 3. 헬스 체크 API

**요청**:
```http
GET /api/v1/recommendations/health
```

**응답**:
```json
{
  "status": "ok"
}
```

---

## 전체 동작 흐름

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         추천 처리 흐름                                   │
└─────────────────────────────────────────────────────────────────────────┘

클라이언트 요청
GET /api/v1/recommendations/USER-000001
          │
          ▼
┌─────────────────────────────────┐
│   RecommendationController      │
│   (요청 받기)                   │
└─────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────┐
│   RecommendationService         │
│   (추천 로직)                   │
└─────────────────────────────────┘
          │
          │ 1. 유저 취향 벡터 조회
          ▼
┌─────────────────────────────────┐
│   UserPreferenceRepository      │
│   Redis → ES 폴백              │
└─────────────────────────────────┘
          │
          │ 취향 벡터 있음?
          │
     ┌────┴────┐
     │         │
    있음      없음 (Cold Start)
     │         │
     ▼         ▼
┌─────────┐  ┌─────────────────┐
│  KNN    │  │ PopularProducts │
│  검색   │  │ Cache           │
└────┬────┘  └────────┬────────┘
     │                │
     ▼                ▼
┌─────────────────────────────────┐
│   Elasticsearch                 │
│   product_index (KNN Search)    │
└─────────────────────────────────┘
          │
          ▼
    응답 반환
```

---

## 핵심 파일 상세 설명

### 1. RecommendationController.kt (API 입구)

**역할**: HTTP 요청을 받아서 서비스에 전달

```kotlin
@RestController
@RequestMapping("/api/v1/recommendations")
class RecommendationController(
    private val recommendationService: RecommendationService,
    private val virtualThreadDispatcher: CoroutineDispatcher  // Virtual Thread Dispatcher 주입
) {

    @GetMapping("/{userId}")
    fun getRecommendations(
        @PathVariable userId: String,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(required = false) category: String?,
        @RequestParam(defaultValue = "true") excludeViewed: Boolean
    ): ResponseEntity<RecommendationResponse> {

        // Virtual Thread dispatcher로 runBlocking 실행
        // Blocking I/O 발생 시에도 Virtual Thread가 unmount되어 처리량 유지
        val response = runBlocking(virtualThreadDispatcher) {
            recommendationService.getRecommendations(
                userId = userId,
                limit = limit.coerceIn(1, 50),  // 1~50 범위로 제한
                category = category,
                excludeViewed = excludeViewed
            )
        }

        return ResponseEntity.ok(response)
    }

    @GetMapping("/popular")
    fun getPopularProducts(
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(required = false) category: String?
    ): ResponseEntity<RecommendationResponse> {
        val response = runBlocking(virtualThreadDispatcher) {
            recommendationService.getRecommendations(
                userId = "_anonymous_",  // 임의의 ID로 Cold Start 트리거
                limit = limit.coerceIn(1, 50),
                category = category,
                excludeViewed = false
            )
        }
        return ResponseEntity.ok(response)
    }
}
```

> **Virtual Thread Dispatcher**: `DispatcherConfig.kt`에서 정의된 Virtual Thread 기반 Dispatcher입니다.
> 코루틴이 Blocking I/O를 만나면 Virtual Thread가 자동으로 unmount되어 다른 요청을 처리할 수 있습니다.

#### @RequestParam vs @PathVariable

```
URL: /api/v1/recommendations/USER-001?limit=10&category=ELECTRONICS
                              ^^^^^^^^
                              PathVariable (경로에 있음)

                                       ^^^^^  ^^^^^^^^^^^^^^^^
                                       RequestParam (? 뒤에 있음)
```

---

### 2. RecommendationService.kt (핵심 로직)

**역할**: 추천 전략 결정 및 실행

```kotlin
@Service
class RecommendationService(
    private val userPreferenceRepository: UserPreferenceRepository,
    private val popularProductsCache: PopularProductsCache,
    private val esClient: ElasticsearchClient
) {

    suspend fun getRecommendations(
        userId: String,
        limit: Int,
        category: String?,
        excludeViewed: Boolean
    ): RecommendationResponse {

        // 1. 유저 취향 벡터 조회
        val preferenceVector = userPreferenceRepository.get(userId)

        // 2. 전략 결정
        val (products, strategy) = if (preferenceVector == null) {
            // Cold Start: 취향 벡터 없음 → 인기 상품 추천
            Pair(getColdStartRecommendations(limit, category), "popularity")
        } else {
            // KNN 검색: 취향 벡터 있음 → 비슷한 상품 찾기
            Pair(searchSimilarProducts(preferenceVector, limit, category), "knn")
        }

        return RecommendationResponse(userId, products, strategy, latencyMs)
    }
}
```

#### Cold Start 문제란?

```
신규 유저 또는 행동 기록이 없는 유저:
- 취향 벡터가 없음 (뭘 좋아하는지 모름)
- 개인화 추천 불가능!

해결책:
- 인기 상품 추천 (다들 사니까 좋은 거겠지?)
- 또는 카테고리별 베스트 상품 추천
```

---

### 3. KNN 검색 이해하기

**KNN = K-Nearest Neighbors = K개의 가장 가까운 이웃**

```kotlin
private fun searchSimilarProducts(
    queryVector: FloatArray,  // 유저 취향 벡터
    k: Int,                   // 찾을 개수
    category: String?
): List<ProductRecommendation> {

    val response = esClient.search({ s ->
        s.index("product_index")
            .knn { knn ->
                knn.field("productVector")        // 상품 벡터 필드
                    .queryVector(queryVector.toList())  // 비교할 벡터
                    .k(k.toLong())                // 10개 찾기
                    .numCandidates((k * 10).toLong())   // 후보 100개 중에서
                    .filter(filterQueries)        // 카테고리, 재고 필터
            }
    }, ProductDocument::class.java)

    // 결과 변환
    return response.hits().hits().map { hit ->
        ProductRecommendation(
            productId = hit.source()?.productId,
            productName = hit.source()?.productName,
            score = hit.score()  // 유사도 점수 (0~1)
        )
    }
}
```

#### KNN 검색 시각화

```
유저 취향 벡터 (빨간 점)
        │
        ▼
    ┌─────────────────────────────────────┐
    │                                     │
    │    ○ 가죽지갑      ○ 운동화        │
    │         ○ 핸드백                    │
    │    ★ ← 유저 취향                   │
    │         ○ 벨트      ○ 티셔츠       │
    │    ○ 시계                          │
    │                    ○ 청바지        │
    └─────────────────────────────────────┘

KNN 검색 결과 (k=3):
1. 핸드백 (거리: 0.1) ← 가장 가까움
2. 가죽지갑 (거리: 0.15)
3. 벨트 (거리: 0.2)

→ 이 3개 상품 추천!
```

#### num_candidates란?

```
상품 100만 개 중에서 가장 비슷한 10개를 찾으려면?

방법 1: 100만 개 전부 비교 → 매우 느림!

방법 2 (HNSW 알고리즘):
  1. 후보 100개(num_candidates)만 대략 추림
  2. 그 100개 중에서 정확히 10개(k) 선택
  → 빠르면서도 정확!

num_candidates = k × 10 (권장값)
```

---

### 4. PopularProductsCache.kt (인기 상품)

**역할**: Cold Start 유저에게 보여줄 인기 상품 캐싱

```kotlin
@Component
class PopularProductsCache(
    private val esClient: ElasticsearchClient,
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) {

    suspend fun getTopProducts(limit: Int): List<ProductRecommendation> {
        // 1. Redis 캐시 확인
        val cached = redisTemplate.opsForValue()
            .get("popular:global")
            .awaitSingleOrNull()

        if (cached != null) {
            return parseProducts(cached).take(limit)  // 캐시 히트!
        }

        // 2. ES에서 조회 (최근 7일 구매 집계)
        val products = queryPopularProducts(limit = 100)

        // 3. Redis에 캐싱 (10분 TTL)
        redisTemplate.opsForValue()
            .set("popular:global", toJson(products), Duration.ofMinutes(10))
            .awaitSingle()

        return products.take(limit)
    }

    private fun queryPopularProducts(limit: Int): List<ProductRecommendation> {
        // ES 집계 쿼리:
        // 최근 7일간 PURCHASE가 많은 상품 순으로 정렬
        return esClient.search({ s ->
            s.index("user_behavior_index")
                .query { q ->
                    q.bool { b ->
                        b.must { m -> m.term { t -> t.field("actionType").value("PURCHASE") } }
                        b.must { m -> m.range { r -> r.field("timestamp").gte("now-7d") } }
                    }
                }
                .aggregations("popular_products") { agg ->
                    agg.terms { t -> t.field("productId").size(limit) }
                }
        })
        // ... 결과 파싱
    }
}
```

#### 인기 상품 캐싱 흐름

```
요청 1: 인기 상품 조회
        │
        ▼
    Redis 확인 → 없음 (Cache Miss)
        │
        ▼
    ES에서 집계 쿼리 실행 (느림, 약 100ms)
        │
        ▼
    Redis에 저장 (TTL: 10분)
        │
        ▼
    응답 반환

요청 2~N: 인기 상품 조회 (10분 이내)
        │
        ▼
    Redis 확인 → 있음! (Cache Hit, 빠름 0.1ms)
        │
        ▼
    바로 응답 반환
```

#### 3단계 폴백 전략 (데이터 부족 시)

행동 데이터가 부족한 경우를 위한 폴백 전략:

```kotlin
// PopularProductsCache.kt
private fun queryPopularProducts(category: String?, limit: Int): List<ProductRecommendation> {
    // 1단계: PURCHASE 집계 시도
    var productIds = queryBehaviorAggregation(
        actionTypes = listOf("PURCHASE"),
        limit = limit
    )

    // 2단계: PURCHASE 없으면 VIEW/CLICK 집계
    if (productIds.isEmpty()) {
        productIds = queryBehaviorAggregation(
            actionTypes = listOf("VIEW", "CLICK"),
            limit = limit
        )
    }

    // 3단계: VIEW/CLICK도 없으면 최신 상품 조회
    if (productIds.isEmpty()) {
        return getLatestProducts(category = category, limit = limit)
    }

    return enrichWithProductInfo(productIds)
}
```

| 단계 | 데이터 소스 | 조건 |
|------|------------|------|
| 1단계 | PURCHASE 집계 | 기본 (구매 데이터 있음) |
| 2단계 | VIEW/CLICK 집계 | 구매 데이터 없음 |
| 3단계 | 최신 등록 상품 | 행동 데이터 전무 |

#### Thundering Herd 방지

캐시 만료 시 동시 요청이 몰리는 문제를 Mutex로 해결:

```kotlin
// PopularProductsCache.kt
private val globalCacheMutex = Mutex()
private val categoryCacheMutexMap = ConcurrentHashMap<String, Mutex>()

suspend fun getTopProducts(limit: Int): List<ProductRecommendation> {
    // 1차 캐시 확인
    val cached = redisTemplate.opsForValue().get(CACHE_KEY_GLOBAL).awaitSingleOrNull()
    if (cached != null) return parseProducts(cached)

    // Double-check locking: 동시 요청 중 하나만 ES 쿼리 실행
    return globalCacheMutex.withLock {
        // 2차 캐시 확인 (다른 요청이 이미 채웠을 수 있음)
        val doubleCheck = redisTemplate.opsForValue().get(CACHE_KEY_GLOBAL).awaitSingleOrNull()
        if (doubleCheck != null) return@withLock parseProducts(doubleCheck)

        // ES 쿼리 실행 및 캐시 저장
        val products = queryPopularProducts(null, 100)
        cacheProducts(CACHE_KEY_GLOBAL, products)
        products.take(limit)
    }
}
```

**효과**: 캐시 미스 시 ES 쿼리가 1번만 실행됨 (N개 동시 요청 → 1번 쿼리)

---

### 5. RecommendationModels.kt (응답 모델)

```kotlin
// API 응답 형태
data class RecommendationResponse(
    val userId: String,                              // 유저 ID
    val recommendations: List<ProductRecommendation>, // 추천 상품 목록
    val strategy: String,                            // "knn" | "popularity" | "category_best" | "fallback"
    val latencyMs: Long                              // 처리 시간 (밀리초)
)

// 추천 상품 하나
data class ProductRecommendation(
    val productId: String,     // 상품 ID
    val productName: String,   // 상품명
    val category: String,      // 카테고리
    val price: Float,          // 가격
    val score: Double = 0.0    // 유사도 점수 (KNN일 때만)
)
```

---

## 추천 전략 정리

| 전략 | 언제 사용? | 방식 |
|------|-----------|------|
| `knn` | 취향 벡터가 있는 유저 | 벡터 유사도 검색 |
| `popularity` | 취향 벡터가 없는 유저 | 인기 상품 반환 |
| `category_best` | 카테고리 지정 + Cold Start | 카테고리별 인기 상품 |
| `fallback` | 오류 발생 시 | 기본 인기 상품 |

---

## 설정 파일 (application.yml)

### 포트 설정

| 환경 | 포트 | 설명 |
|------|------|------|
| 로컬 개발 | 8080 | 기본값 (`SERVER_PORT` 환경변수로 변경 가능) |
| Docker | 8082 | `docker` 프로파일 활성화 시 |

```yaml
# 기본 설정 (로컬)
server:
  port: ${SERVER_PORT:8080}

spring:
  threads:
    virtual:
      enabled: true  # Virtual Threads 활성화

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

# UserPreferenceRepository Redis 조회 설정
# Redis 장애 시 빠른 실패를 위한 500ms timeout 적용 (코드 내 하드코딩)
# private const val REDIS_TIMEOUT_MS = 500L

elasticsearch:
  host: ${ELASTICSEARCH_HOST:localhost}
  port: ${ELASTICSEARCH_PORT:9200}

recommendation:
  knn:
    k: ${RECOMMENDATION_K:10}  # 기본 추천 개수
  cache:
    popular-ttl-minutes: 10    # 인기 상품 캐시 유지 시간
    global-cache-size: 100     # 전체 인기 상품 캐시 크기
    category-cache-size: 50    # 카테고리별 캐시 크기

---
# Docker 프로파일
spring:
  config:
    activate:
      on-profile: docker

  data:
    redis:
      host: redis

elasticsearch:
  host: elasticsearch

server:
  port: 8082  # Docker 환경에서는 8082 사용
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

## 실행 및 테스트

### 실행
```bash
./gradlew :recommendation-api:bootRun
```

### 테스트
```bash
# 개인화 추천
curl "http://localhost:8080/api/v1/recommendations/USER-000001?limit=5"

# 인기 상품
curl "http://localhost:8080/api/v1/recommendations/popular?limit=10"

# 카테고리 필터
curl "http://localhost:8080/api/v1/recommendations/USER-000001?category=ELECTRONICS"

# 헬스체크
curl "http://localhost:8080/api/v1/recommendations/health"
```

---

## 성능 지표

| 상황 | 예상 응답 시간 |
|------|---------------|
| Redis 캐시 히트 | 5~10ms |
| KNN 검색 (캐시 미스) | 30~50ms |
| 인기 상품 (캐시 히트) | 5~10ms |
| 인기 상품 (캐시 미스) | 50~100ms |

---

## 메트릭

### 핵심 메트릭

| 메트릭 이름 | 의미 |
|------------|------|
| `recommendation.latency` | 추천 API 응답 시간 |
| `recommendation.strategy.knn` | KNN 전략 사용 횟수 |
| `recommendation.strategy.popularity` | 인기 전략 사용 횟수 |
| `recommendation.strategy.category_best` | 카테고리별 인기 전략 사용 횟수 |
| `recommendation.strategy.fallback` | 폴백 전략 사용 횟수 (오류 발생 시) |

### 추가 메트릭 (상세)

| 메트릭 이름 | 의미 |
|------------|------|
| `recommendation.strategy.category_best` | 카테고리 베스트 전략 사용 횟수 |
| `recommendation.knn.failed` | KNN 검색 실패 수 |
| `recommendation.fallback.used` | 오류로 인기 상품 폴백 사용 횟수 |
| `recommendation.result.empty` | 빈 결과 반환 횟수 |
| `popular.cache.hit` | 인기 상품 캐시 히트 |
| `popular.cache.miss` | 인기 상품 캐시 미스 |
| `preference.cache.hit` | 취향 벡터 캐시 히트 |
| `preference.cache.miss` | 취향 벡터 캐시 미스 |
| `preference.es.fallback` | ES 폴백 조회 횟수 |

---

## 핵심 요약

| 항목 | 내용 |
|------|------|
| 역할 | 개인화 상품 추천 API |
| 포트 | 8080 (로컬), 8082 (Docker) |
| 핵심 API | `GET /api/v1/recommendations/{userId}` |
| 추천 전략 | KNN (개인화), Popularity (Cold Start) |
| 캐시 | Redis (취향 벡터, 인기 상품) |
| 검색 | Elasticsearch KNN |
| 벡터 차원 | 384 (multilingual-e5-base) |
