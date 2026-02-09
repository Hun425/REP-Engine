# ADR-004: 벡터 저장소 선택

## 상태 (Status)
**채택됨 (Accepted)**

## 배경 (Context)

REP-Engine은 두 종류의 벡터 데이터를 저장하고 검색해야 합니다:

1. **상품 벡터 (Product Vector):** 상품 정보를 기반으로 생성된 임베딩. KNN 검색의 대상
2. **유저 취향 벡터 (User Preference Vector):** 유저의 행동 기록을 기반으로 실시간 업데이트되는 벡터. KNN 검색의 쿼리

각 벡터의 특성에 맞는 저장소 선택이 필요합니다.

### 요구사항

| 요구사항 | 상품 벡터 | 유저 취향 벡터 |
|---------|----------|---------------|
| 데이터 수 | ~100만 개 | ~1000만 개 (활성 유저) |
| 갱신 빈도 | 낮음 (상품 등록/수정 시) | 높음 (유저 행동 시마다) |
| 검색 대상 | O (KNN 검색) | X (조회만) |
| 지연 시간 | 100ms 이하 | 10ms 이하 |
| 영속성 | 필수 | 선택 (재계산 가능) |

## 검토한 대안 (Alternatives Considered)

### Option 1: Elasticsearch Only

모든 벡터를 ES에 저장

| 장점 | 단점 |
|-----|-----|
| 단일 저장소로 운영 단순화 | 유저 벡터 잦은 갱신 시 인덱싱 부하 |
| KNN + 필터 검색 한 번에 가능 | 1000만 유저 벡터 인덱스 크기 거대 |
| 복제/백업 통합 관리 | 실시간 갱신 후 검색 반영까지 refresh_interval 지연 |

### Option 2: Redis Only (RedisSearch)

Redis의 벡터 검색 기능 활용

| 장점 | 단점 |
|-----|-----|
| 초저지연 읽기/쓰기 | 메모리 비용 높음 (768 dim × 100만 = ~3GB) |
| 실시간 갱신 즉시 반영 | ES 대비 벡터 검색 기능 제한적 |
| 단순한 구조 | 복잡한 필터 조건 지원 미흡 |

### Option 3: 전용 벡터 DB (Pinecone, Milvus, Qdrant)

| 장점 | 단점 |
|-----|-----|
| 벡터 검색 최적화 | 추가 인프라 관리 |
| 대규모 벡터 처리 특화 | 학습 비용 |
| 다양한 인덱스 알고리즘 | ES 이미 있는데 중복 투자 |

### Option 4: Hybrid (ES + Redis)

- **ES:** 상품 벡터 저장 + KNN 검색
- **Redis:** 유저 취향 벡터 캐싱 + 빠른 조회/갱신

| 장점 | 단점 |
|-----|-----|
| 각 저장소의 강점 활용 | 두 시스템 관리 필요 |
| 유저 벡터 실시간 갱신 용이 | 데이터 동기화 로직 필요 |
| ES 인덱싱 부하 분산 | |

## 결정 (Decision)

**Hybrid 방식 (Elasticsearch + Redis)**을 채택합니다.

### 역할 분담

| 데이터 | 저장소 | 이유 |
|-------|-------|-----|
| 상품 벡터 | Elasticsearch | KNN 검색 대상, 풍부한 필터 조건 필요 |
| 유저 취향 벡터 | Redis (1차) + ES (2차) | 실시간 갱신, 빠른 조회 필요 |

### 유저 벡터 저장 전략

```
                     ┌─────────────────────────────────────┐
                     │          User Action Event          │
                     └──────────────┬──────────────────────┘
                                    │
                                    ▼
                     ┌─────────────────────────────────────┐
                     │      Calculate New Preference       │
                     │      (EMA with product vector)      │
                     └──────────────┬──────────────────────┘
                                    │
                     ┌──────────────┴──────────────┐
                     ▼                             ▼
        ┌─────────────────────┐       ┌─────────────────────┐
        │   Redis (Primary)   │       │   ES (Async Sync)   │
        │   - TTL: 24h        │──────▶│   - Bulk update     │
        │   - 즉시 갱신       │       │   - 1분 주기        │
        └─────────────────────┘       └─────────────────────┘
                     │
                     ▼
        ┌─────────────────────┐
        │   Recommendation    │
        │   - Redis에서 조회  │
        │   - 없으면 ES 조회  │
        │   - Cold Start 폴백 │
        └─────────────────────┘
```

## 상세 설계 (Implementation)

### Redis 스키마

```
Key Pattern: user:preference:{userId}
Value: JSON or MessagePack encoded vector
TTL: 86400 (24시간)

예시:
KEY: user:preference:U12345
VALUE: {"preferenceVector": [0.12, -0.45, 0.78, ...], "actionCount": 1, "updatedAt": 1704067200000}
```

### Redis 구현 (Kotlin)

```kotlin
@Repository
class UserPreferenceRepository(
    private val redisTemplate: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    private val keyPrefix = "user:preference:"
    private val ttl = Duration.ofHours(24)

    suspend fun save(userId: String, vector: FloatArray, actionCount: Int = 1) {
        val key = "$keyPrefix$userId"
        val data = UserPreferenceData(
            preferenceVector = vector.toList(),
            actionCount = actionCount,
            updatedAt = System.currentTimeMillis()
        )
        redisTemplate.opsForValue()
            .set(key, objectMapper.writeValueAsString(data), ttl)
            .awaitSingle()
    }

    suspend fun get(userId: String): FloatArray? {
        val key = "$keyPrefix$userId"
        return redisTemplate.opsForValue()
            .get(key)
            .awaitSingleOrNull()
            ?.let { objectMapper.readValue<UserPreferenceData>(it).preferenceVector.toFloatArray() }
    }
}

data class UserPreferenceData(
    val preferenceVector: List<Float>,
    val actionCount: Int = 1,
    val updatedAt: Long
)
```

### Elasticsearch 인덱스 (유저 취향 벡터 백업용)

```json
PUT /user_preference_index
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "refresh_interval": "60s"
  },
  "mappings": {
    "properties": {
      "userId": { "type": "keyword" },
      "preferenceVector": {
        "type": "dense_vector",
        "dims": 768,
        "index": false
      },
      "actionCount": { "type": "integer" },
      "updatedAt": { "type": "date" }
    }
  }
}
```

> **Note:** `index: false`로 설정하여 KNN 인덱스를 생성하지 않음. 조회만 필요하므로 인덱싱 오버헤드 제거.

### 추천 서비스 로직

```kotlin
@Service
class RecommendationService(
    private val userPreferenceRepository: UserPreferenceRepository,
    private val elasticsearchClient: ElasticsearchClient,
    private val popularProductsCache: PopularProductsCache
) {
    suspend fun getRecommendations(userId: String, limit: Int = 10): List<Product> {
        // 1. Redis에서 유저 취향 벡터 조회
        val preferenceVector = userPreferenceRepository.get(userId)

        // 2. Cold Start 처리: 벡터가 없으면 인기 상품 반환
        if (preferenceVector == null) {
            return popularProductsCache.getTopProducts(limit)
        }

        // 3. ES KNN 검색
        return searchSimilarProducts(preferenceVector, limit)
    }

    private suspend fun searchSimilarProducts(
        queryVector: FloatArray,
        k: Int
    ): List<Product> {
        val response = elasticsearchClient.search { s ->
            s.index("product_index")
                .knn { knn ->
                    knn.field("productVector")
                        .queryVector(queryVector.toList())
                        .k(k)
                        .numCandidates(k * 10)  // k의 10배로 후보 탐색
                }
        }
        return response.hits().hits().map { it.source()!! }
    }
}
```

### ES -> Redis 캐시 워밍 (배치)

Redis 장애 복구 또는 콜드 스타트 시 ES 데이터로 Redis 복원:

```kotlin
@Scheduled(fixedRate = 3600000)  // 1시간마다
suspend fun warmUpCache() {
    val scrollResponse = elasticsearchClient.search { s ->
        s.index("user_preference_index")
            .scroll(Time.of { t -> t.time("1m") })
            .size(1000)
            .query { q ->
                q.range { r ->
                    r.field("lastUpdated")
                        .gte(JsonData.of("now-24h"))
                }
            }
    }

    scrollResponse.hits().hits().forEach { hit ->
        val doc = hit.source()!!
        userPreferenceRepository.save(doc.userId, doc.preferenceVector)
    }
}
```

## 메모리 산정

### Redis 메모리 사용량

| 항목 | 계산 |
|-----|-----|
| 벡터 크기 | 768 dims × 4 bytes = 3,072 bytes |
| 메타데이터 (key, timestamp) | ~100 bytes |
| Redis 오버헤드 | ~50% |
| **유저당 총 메모리** | ~4.8 KB |
| **1000만 유저** | ~48 GB |

권장 Redis 설정: 32GB 메모리, `maxmemory-policy: allkeys-lru`

### Elasticsearch 인덱스 크기

| 인덱스 | 문서 수 | 벡터 | 예상 크기 |
|-------|--------|------|----------|
| product_index | 100만 | indexed (KNN) | ~10 GB |
| user_preference_index | 1000만 | non-indexed | ~20 GB |
| user_behavior_index | 1억 (30일) | 없음 | ~50 GB |

## 결과 (Consequences)

### 긍정적 효과
- 유저 벡터 갱신이 ES 인덱싱 부하를 유발하지 않음
- 추천 API 지연 시간 < 50ms 달성 가능
- 각 저장소의 강점을 최대한 활용

### 부정적 효과 / 트레이드오프
- Redis 장애 시 ES 폴백 필요 → 정기 동기화로 데이터 정합성 유지
- 두 저장소 간 데이터 불일치 가능성 → 최종 일관성(Eventual Consistency) 수용
- 운영 복잡도 증가 → 모니터링 강화로 대응

## 관련 문서
- [ADR-003: Embedding 모델 선택](./adr-003-embedding-model.md)
- [Phase 3: 실시간 추천 엔진](./phase%203.md)
- [Infrastructure: 인프라 구성](./infrastructure.md)