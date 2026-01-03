# Phase 3: 실시간 추천 엔진 (Vector Search)

본 문서는 사용자의 실시간 행동 데이터를 기반으로 Elasticsearch의 벡터 검색(KNN)을 활용하여 개인화된 상품을 추천하는 로직의 상세 설계를 다룹니다.

## 1. 핵심 개념: 벡터 검색 (Vector Search)

### 1.1 벡터 검색이란?

전통적인 키워드 검색이 '문자열 일치'를 찾는다면, 벡터 검색은 상품과 유저의 취향을 **n차원 공간의 좌표(Vector)**로 표현하고 **거리가 가까운 항목**을 찾습니다.

```
           상품 벡터 공간 (2D 예시)

     전자기기        ●상품A (스마트폰)
        ↑           ●상품B (노트북)
        │      ★유저 취향
        │           ●상품C (태블릿)
        │
        └───────────────────→ 가격대

     ★ 유저 취향과 가장 가까운 상품 = 상품B (추천!)
```

### 1.2 벡터 유형

| 벡터 | 생성 시점 | 저장소 | 용도 |
|-----|----------|-------|------|
| **Product Vector** | 상품 등록/수정 시 | Elasticsearch | KNN 검색 대상 |
| **User Preference Vector** | 유저 행동 발생 시 | Redis (캐시) + ES (백업) | KNN 검색 쿼리 |


## 2. 추천 파이프라인 (Recommendation Pipeline)

### 2.1 전체 흐름

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          Recommendation Pipeline                                 │
└─────────────────────────────────────────────────────────────────────────────────┘

  [유저 행동]                                              [추천 요청]
       │                                                        │
       ▼                                                        ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ 1. Action    │    │ 2. Vector    │    │ 3. Store     │    │ 4. KNN       │
│    Event     │───▶│    Lookup    │───▶│    Update    │    │    Search    │
│              │    │ (Product)    │    │  (Redis)     │    │              │
└──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
                                                                   │
                                                                   ▼
                    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
                    │ 7. Response  │◀───│ 6. Post      │◀───│ 5. ES        │
                    │              │    │    Process   │    │    Query     │
                    └──────────────┘    └──────────────┘    └──────────────┘
```

### 2.2 단계별 상세

| 단계 | 설명 | 지연 시간 |
|-----|------|----------|
| 1. Action Event | Kafka를 통해 유저 행동(VIEW, CLICK) 수신 | - |
| 2. Vector Lookup | 행동한 상품의 벡터를 ES에서 조회 | ~5ms |
| 3. Store Update | EMA로 취향 벡터 갱신 후 Redis 저장 | ~2ms |
| 4. KNN Search | 추천 요청 시 Redis에서 취향 벡터 조회 | ~2ms |
| 5. ES Query | 취향 벡터로 KNN 검색 실행 | ~20ms |
| 6. Post Process | 필터링, 재정렬, 비즈니스 로직 적용 | ~5ms |
| 7. Response | 추천 결과 반환 | - |
| **총합** | | **< 50ms** |


## 3. Elasticsearch KNN 설정

### 3.1 `product_index` 매핑

```json
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "index.knn": true
  },
  "mappings": {
    "properties": {
      "productId": { "type": "keyword" },
      "productName": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "keyword": { "type": "keyword" }
        }
      },
      "category": { "type": "keyword" },
      "subCategory": { "type": "keyword" },
      "brand": { "type": "keyword" },
      "price": { "type": "float" },
      "stock": { "type": "integer" },
      "description": { "type": "text" },
      "tags": { "type": "keyword" },
      "productVector": {
        "type": "dense_vector",
        "dims": 384,
        "index": true,
        "similarity": "cosine",
        "index_options": {
          "type": "hnsw",
          "m": 16,
          "ef_construction": 100
        }
      },
      "createdAt": { "type": "date" },
      "updatedAt": { "type": "date" }
    }
  }
}
```

### 3.2 KNN 파라미터 결정 근거

| 파라미터 | 값 | 근거 |
|---------|-----|------|
| `dims` | **384** | multilingual-e5-base 모델 출력 차원. 768이나 1536 대비 인덱스 크기 50% 이상 절감, 검색 속도 향상 |
| `similarity` | **cosine** | 텍스트 임베딩에 가장 적합. 벡터 크기보다 방향(의미)이 중요 |
| `m` | **16** | HNSW 그래프의 연결 수. 기본값 16이 recall과 속도의 균형점 |
| `ef_construction` | **100** | 인덱싱 시 탐색 범위. 높을수록 정확하지만 인덱싱 느림. 100은 recall 95%+ 보장 |

### 3.3 KNN Query 파라미터

```json
POST /product_index/_search
{
  "knn": {
    "field": "productVector",
    "query_vector": [0.15, -0.42, 0.01, ...],
    "k": 10,
    "num_candidates": 100
  },
  "fields": ["productId", "productName", "category", "price"]
}
```

| 파라미터 | 값 | 근거 |
|---------|-----|------|
| `k` | **10** | 추천 결과 수. 일반적인 추천 카드 UI에 10개 적합 |
| `num_candidates` | **100** | k의 10배. 후보군 확대로 recall 향상. 너무 크면 지연 시간 증가 |

> **Note:** `num_candidates`가 클수록 정확도는 높아지지만 지연 시간도 증가합니다. 상품 수 100만 기준 k=10, num_candidates=100에서 ~20ms 응답 시간을 달성할 수 있습니다.


## 4. 추천 API 구현

### 4.1 API 명세

```
GET /api/v1/recommendations/{userId}

Query Parameters:
- limit: 추천 개수 (default: 10, max: 50)
- category: 카테고리 필터 (optional)
- excludeViewed: 이미 본 상품 제외 (default: true)

Response:
{
  "userId": "U12345",
  "recommendations": [
    {
      "productId": "P001",
      "productName": "삼성 갤럭시 S24",
      "category": "전자기기",
      "price": 1200000,
      "score": 0.92
    },
    ...
  ],
  "strategy": "knn",  // knn | popularity | category_best
  "latencyMs": 45
}
```

### 4.2 Service 구현

```kotlin
@Service
class RecommendationService(
    private val userPreferenceRepository: UserPreferenceRepository,
    private val esClient: ElasticsearchClient,
    private val popularProductsCache: PopularProductsCache,
    private val userBehaviorRepository: UserBehaviorRepository,
    private val meterRegistry: MeterRegistry
) {
    private val latencyTimer = Timer.builder("recommendation.latency")
        .register(meterRegistry)

    suspend fun getRecommendations(
        userId: String,
        limit: Int = 10,
        category: String? = null,
        excludeViewed: Boolean = true
    ): RecommendationResponse {
        return latencyTimer.recordSuspend {
            val startTime = System.currentTimeMillis()

            // 1. 유저 취향 벡터 조회
            val preferenceVector = userPreferenceRepository.get(userId)

            // 2. 전략 결정 및 추천 실행
            val (products, strategy) = when {
                preferenceVector == null -> {
                    // Cold Start: 인기 상품 반환
                    Pair(getColdStartRecommendations(limit, category), "popularity")
                }
                else -> {
                    // KNN 검색
                    val excludeIds = if (excludeViewed) {
                        userBehaviorRepository.getRecentViewedProducts(userId, 100)
                    } else emptyList()

                    Pair(
                        searchSimilarProducts(preferenceVector, limit, category, excludeIds),
                        "knn"
                    )
                }
            }

            RecommendationResponse(
                userId = userId,
                recommendations = products,
                strategy = strategy,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private suspend fun searchSimilarProducts(
        queryVector: FloatArray,
        k: Int,
        category: String?,
        excludeIds: List<String>
    ): List<ProductRecommendation> {
        val response = esClient.search({ s ->
            s.index("product_index")
                .knn { knn ->
                    knn.field("productVector")
                        .queryVector(queryVector.toList())
                        .k(k)
                        .numCandidates(k * 10)
                        // 필터 적용
                        .filter { f ->
                            f.bool { b ->
                                // 카테고리 필터
                                category?.let { cat ->
                                    b.must { m -> m.term { t -> t.field("category").value(cat) } }
                                }
                                // 이미 본 상품 제외
                                if (excludeIds.isNotEmpty()) {
                                    b.mustNot { mn -> mn.ids { ids -> ids.values(excludeIds) } }
                                }
                                // 재고 있는 상품만
                                b.must { m -> m.range { r -> r.field("stock").gt(JsonData.of(0)) } }
                                b
                            }
                        }
                }
                .source { src -> src.includes("productId", "productName", "category", "price") }
        }, Product::class.java)

        return response.hits().hits().map { hit ->
            ProductRecommendation(
                productId = hit.source()!!.productId,
                productName = hit.source()!!.productName,
                category = hit.source()!!.category,
                price = hit.source()!!.price,
                score = hit.score() ?: 0.0
            )
        }
    }

    private suspend fun getColdStartRecommendations(
        limit: Int,
        category: String?
    ): List<ProductRecommendation> {
        return if (category != null) {
            // 카테고리별 베스트
            popularProductsCache.getCategoryBest(category, limit)
        } else {
            // 전체 인기 상품
            popularProductsCache.getTopProducts(limit)
        }
    }
}
```


## 5. Cold Start 처리

### 5.1 Cold Start란?

신규 유저 또는 행동 데이터가 부족한 유저에게는 취향 벡터가 없어 KNN 검색이 불가능합니다.

### 5.2 해결 전략

```
┌────────────────────────────────────────────────────────────────┐
│                    Cold Start Decision Tree                     │
└────────────────────────────────────────────────────────────────┘

                    ┌─────────────────┐
                    │ 유저 취향 벡터  │
                    │    존재?        │
                    └────────┬────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
              ▼                             ▼
        ┌─────────┐                   ┌─────────┐
        │   Yes   │                   │   No    │
        └────┬────┘                   └────┬────┘
             │                             │
             ▼                             ▼
      ┌──────────────┐           ┌────────────────────┐
      │  KNN Search  │           │  카테고리 지정?    │
      └──────────────┘           └─────────┬──────────┘
                                           │
                              ┌────────────┴────────────┐
                              │                         │
                              ▼                         ▼
                        ┌─────────┐               ┌─────────┐
                        │   Yes   │               │   No    │
                        └────┬────┘               └────┬────┘
                             │                         │
                             ▼                         ▼
                   ┌─────────────────┐      ┌─────────────────┐
                   │ Category Best   │      │ Global Popular  │
                   │ (카테고리 인기) │      │ (전체 인기)     │
                   └─────────────────┘      └─────────────────┘
```

### 5.3 인기 상품 캐시 구현

```kotlin
@Component
class PopularProductsCache(
    private val esClient: ElasticsearchClient,
    private val redisTemplate: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    private val cacheKeyGlobal = "popular:global"
    private val cacheKeyCategory = "popular:category:"
    private val cacheTtl = Duration.ofMinutes(10)

    /**
     * 전체 인기 상품 조회
     * - Redis 캐시 우선
     * - 캐시 미스 시 ES에서 집계 후 캐싱
     */
    suspend fun getTopProducts(limit: Int): List<ProductRecommendation> {
        // 1. Redis 캐시 확인
        val cached = redisTemplate.opsForValue().get(cacheKeyGlobal).awaitSingleOrNull()
        if (cached != null) {
            return objectMapper.readValue<List<ProductRecommendation>>(cached).take(limit)
        }

        // 2. ES에서 조회 (최근 7일 판매량 기준)
        val products = queryPopularProducts(null, 100)

        // 3. Redis 캐싱
        redisTemplate.opsForValue()
            .set(cacheKeyGlobal, objectMapper.writeValueAsString(products), cacheTtl)
            .awaitSingle()

        return products.take(limit)
    }

    /**
     * 카테고리별 인기 상품 조회
     */
    suspend fun getCategoryBest(category: String, limit: Int): List<ProductRecommendation> {
        val cacheKey = "$cacheKeyCategory$category"

        val cached = redisTemplate.opsForValue().get(cacheKey).awaitSingleOrNull()
        if (cached != null) {
            return objectMapper.readValue<List<ProductRecommendation>>(cached).take(limit)
        }

        val products = queryPopularProducts(category, 50)

        redisTemplate.opsForValue()
            .set(cacheKey, objectMapper.writeValueAsString(products), cacheTtl)
            .awaitSingle()

        return products.take(limit)
    }

    private suspend fun queryPopularProducts(
        category: String?,
        limit: Int
    ): List<ProductRecommendation> {
        // user_behavior_index에서 최근 7일 PURCHASE 집계
        val response = esClient.search({ s ->
            s.index("user_behavior_index")
                .size(0)
                .query { q ->
                    q.bool { b ->
                        b.must { m ->
                            m.term { t -> t.field("actionType").value("PURCHASE") }
                        }
                        b.must { m ->
                            m.range { r -> r.field("timestamp").gte(JsonData.of("now-7d")) }
                        }
                        category?.let { cat ->
                            b.must { m ->
                                m.term { t -> t.field("category").value(cat) }
                            }
                        }
                        b
                    }
                }
                .aggregations("popular_products") { agg ->
                    agg.terms { t ->
                        t.field("productId").size(limit)
                    }
                }
        }, Void::class.java)

        val productIds = response.aggregations()["popular_products"]
            ?.sterms()?.buckets()?.array()
            ?.map { it.key().stringValue() } ?: emptyList()

        // 상품 상세 정보 조회
        return getProductDetails(productIds)
    }

    private suspend fun getProductDetails(productIds: List<String>): List<ProductRecommendation> {
        if (productIds.isEmpty()) return emptyList()

        val response = esClient.mget({ m ->
            m.index("product_index").ids(productIds)
        }, Product::class.java)

        return response.docs().mapNotNull { doc ->
            doc.result()?.source()?.let { product ->
                ProductRecommendation(
                    productId = product.productId,
                    productName = product.productName,
                    category = product.category,
                    price = product.price,
                    score = 0.0  // 인기 상품은 score 없음
                )
            }
        }
    }
}
```


## 6. 유저 취향 벡터 관리

### 6.1 취향 벡터 갱신 로직 (EMA)

```kotlin
/**
 * 지수 이동 평균(Exponential Moving Average)으로 취향 벡터 갱신
 *
 * New = Old × (1 - α) + Current × α
 *
 * α (alpha): 새로운 행동의 가중치
 * - α = 0.3: 최근 행동 반영 빠름 (변화에 민감)
 * - α = 0.1: 최근 행동 반영 느림 (안정적)
 */
@Component
class PreferenceVectorCalculator {

    fun update(
        currentPreference: FloatArray?,
        newProductVector: FloatArray,
        actionType: ActionType
    ): FloatArray {
        // 행동 유형별 가중치
        val alpha = when (actionType) {
            ActionType.PURCHASE -> 0.5f      // 구매: 강한 신호
            ActionType.ADD_TO_CART -> 0.3f   // 장바구니: 중간 강도 신호
            ActionType.CLICK -> 0.3f         // 클릭: 중간 강도 신호
            ActionType.SEARCH -> 0.2f        // 검색: 중간 신호
            ActionType.VIEW -> 0.1f          // 조회: 약한 신호
            ActionType.WISHLIST -> 0.1f      // 위시리스트: 약한 신호
            else -> 0.0f
        }

        if (alpha == 0.0f) return currentPreference ?: newProductVector

        return if (currentPreference == null) {
            newProductVector
        } else {
            FloatArray(currentPreference.size) { i ->
                currentPreference[i] * (1 - alpha) + newProductVector[i] * alpha
            }.normalize()
        }
    }

    private fun FloatArray.normalize(): FloatArray {
        val norm = sqrt(this.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 0) this.map { it / norm }.toFloatArray() else this
    }
}
```

### 6.2 취향 벡터 저장소 (Redis)

```kotlin
@Repository
class UserPreferenceRepository(
    private val redisTemplate: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val esClient: ElasticsearchClient  // 백업용
) {
    private val keyPrefix = "user:preference:"
    private val ttl = Duration.ofHours(24)

    suspend fun save(userId: String, vector: FloatArray) {
        val key = "$keyPrefix$userId"
        val data = UserPreferenceData(
            vector = vector.toList(),
            updatedAt = System.currentTimeMillis()
        )

        // 1. Redis 저장 (Primary)
        redisTemplate.opsForValue()
            .set(key, objectMapper.writeValueAsString(data), ttl)
            .awaitSingle()

        // 2. ES 백업 (Async) - 1분 debounce로 ES 부하 감소
        // 실제 구현에서는 별도 배치 프로세스로 동기화
    }

    suspend fun get(userId: String): FloatArray? {
        val key = "$keyPrefix$userId"

        // 1. Redis에서 조회
        val cached = redisTemplate.opsForValue().get(key).awaitSingleOrNull()
        if (cached != null) {
            return objectMapper.readValue<UserPreferenceData>(cached).vector.toFloatArray()
        }

        // 2. Redis 미스 → ES에서 복구 시도
        return getFromEs(userId)?.also { vector ->
            // Redis에 다시 캐싱
            save(userId, vector)
        }
    }

    private suspend fun getFromEs(userId: String): FloatArray? {
        return try {
            val response = esClient.get({ g ->
                g.index("user_preference_index").id(userId)
            }, UserPreferenceDocument::class.java)

            response.source()?.preferenceVector?.toFloatArray()
        } catch (e: Exception) {
            null
        }
    }
}

data class UserPreferenceData(
    val vector: List<Float>,
    val updatedAt: Long
)
```


## 7. Phase 3 성공 기준 (Exit Criteria)

| 기준 | 측정 방법 | 목표 |
|-----|----------|------|
| 추천 정확도 | 유저가 특정 카테고리 다수 클릭 후 해당 카테고리 상품 추천 비율 | 70% 이상 |
| 응답 속도 | 추천 API P99 레이턴시 | 100ms 이내 |
| 실시간 반영 | 클릭 후 새로운 추천 결과 변화 | 5초 이내 |
| Cold Start | 신규 유저 추천 결과 존재 | 100% (인기 상품) |
| 가용성 | Redis 장애 시 ES 폴백 | 정상 동작 |


## 8. 성능 최적화 팁

### 8.1 ES KNN 성능 튜닝

```yaml
# elasticsearch.yml
index.knn.algo_param.ef_search: 50  # 검색 시 탐색 범위 (기본 100)
```

- `ef_search` 낮추면 속도 향상, recall 감소
- 상품 100만 개 기준 ef_search=50에서 ~15ms, ef_search=100에서 ~25ms

### 8.2 벡터 압축 (Optional)

```json
"productVector": {
  "type": "dense_vector",
  "dims": 384,
  "index": true,
  "similarity": "cosine",
  "element_type": "byte"  // float → byte 변환, 메모리 75% 절감
}
```

> 정확도 약간 손실 있지만, 대규모 데이터에서 유용


## 9. 관련 문서

- [Phase 2: 데이터 파이프라인](./phase%202.md)
- [Phase 4: 실시간 알림 시스템](./phase%204.md)
- [ADR-003: Embedding 모델 선택](./adr-003-embedding-model.md)
- [ADR-004: 벡터 저장소 선택](./adr-004-vector-storage.md)
