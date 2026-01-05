package com.rep.recommendation.service

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.json.JsonData
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rep.recommendation.config.RecommendationProperties
import com.rep.model.ProductDocument
import com.rep.recommendation.model.ProductRecommendation
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * 인기 상품 캐시
 *
 * Cold Start 유저에게 인기 상품을 추천하기 위한 캐시입니다.
 * Redis에 캐싱하고, 캐시 미스 시 ES에서 집계하여 조회합니다.
 *
 * @see docs/phase%203.md - Cold Start 처리
 */
@Component
class PopularProductsCache(
    private val esClient: ElasticsearchClient,
    private val redisTemplate: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val properties: RecommendationProperties,
    meterRegistry: MeterRegistry
) {
    companion object {
        private const val CACHE_KEY_GLOBAL = "popular:global"
        private const val CACHE_KEY_CATEGORY_PREFIX = "popular:category:"
        private const val PRODUCT_INDEX = "product_index"
        private const val BEHAVIOR_INDEX = "user_behavior_index"
    }

    private val cacheTtl = Duration.ofMinutes(properties.cache.popularTtlMinutes)

    private val cacheHitCounter = Counter.builder("popular.cache.hit")
        .description("Popular products cache hits")
        .register(meterRegistry)

    private val cacheMissCounter = Counter.builder("popular.cache.miss")
        .description("Popular products cache misses")
        .register(meterRegistry)

    /**
     * 전체 인기 상품을 조회합니다.
     *
     * @param limit 조회할 개수
     * @return 인기 상품 목록
     */
    suspend fun getTopProducts(limit: Int): List<ProductRecommendation> {
        // 1. Redis 캐시 확인
        val cached = redisTemplate.opsForValue().get(CACHE_KEY_GLOBAL).awaitSingleOrNull()

        if (cached != null) {
            log.debug { "Cache hit for global popular products" }
            cacheHitCounter.increment()
            return objectMapper.readValue<List<ProductRecommendation>>(cached).take(limit)
        }

        // 2. ES에서 조회
        log.debug { "Cache miss for global popular products, querying ES" }
        cacheMissCounter.increment()
        val products = queryPopularProducts(category = null, limit = properties.cache.globalCacheSize)

        // 3. Redis에 캐싱
        if (products.isNotEmpty()) {
            redisTemplate.opsForValue()
                .set(CACHE_KEY_GLOBAL, objectMapper.writeValueAsString(products), cacheTtl)
                .awaitSingle()
        }

        return products.take(limit)
    }

    /**
     * 카테고리별 인기 상품을 조회합니다.
     *
     * @param category 카테고리
     * @param limit 조회할 개수
     * @return 인기 상품 목록
     */
    suspend fun getCategoryBest(category: String, limit: Int): List<ProductRecommendation> {
        val cacheKey = "$CACHE_KEY_CATEGORY_PREFIX$category"

        // 1. Redis 캐시 확인
        val cached = redisTemplate.opsForValue().get(cacheKey).awaitSingleOrNull()

        if (cached != null) {
            log.debug { "Cache hit for category=$category popular products" }
            cacheHitCounter.increment()
            return objectMapper.readValue<List<ProductRecommendation>>(cached).take(limit)
        }

        // 2. ES에서 조회
        log.debug { "Cache miss for category=$category popular products, querying ES" }
        cacheMissCounter.increment()
        val products = queryPopularProducts(category = category, limit = properties.cache.categoryCacheSize)

        // 3. Redis에 캐싱
        if (products.isNotEmpty()) {
            redisTemplate.opsForValue()
                .set(cacheKey, objectMapper.writeValueAsString(products), cacheTtl)
                .awaitSingle()
        }

        return products.take(limit)
    }

    /**
     * ES에서 인기 상품을 조회합니다.
     *
     * 폴백 전략 (Cold Start 대응):
     * 1. PURCHASE 집계 (최근 7일)
     * 2. VIEW/CLICK 집계 (PURCHASE 결과 없을 경우)
     * 3. 최신 등록 상품 (모든 집계 결과 없을 경우)
     */
    private fun queryPopularProducts(category: String?, limit: Int): List<ProductRecommendation> {
        return try {
            // 1. PURCHASE 집계 시도
            var productIds = queryBehaviorAggregation(
                category = category,
                actionTypes = listOf("PURCHASE"),
                limit = limit
            )

            // 2. PURCHASE 없으면 VIEW/CLICK 집계
            if (productIds.isEmpty()) {
                log.debug { "No PURCHASE data, falling back to VIEW/CLICK for category=$category" }
                productIds = queryBehaviorAggregation(
                    category = category,
                    actionTypes = listOf("VIEW", "CLICK"),
                    limit = limit
                )
            }

            // 3. VIEW/CLICK도 없으면 최신 상품 조회
            if (productIds.isEmpty()) {
                log.debug { "No behavior data, falling back to latest products for category=$category" }
                return getLatestProducts(category = category, limit = limit)
            }

            // 상품 상세 정보 조회
            getProductDetails(productIds)

        } catch (e: Exception) {
            log.error(e) { "Failed to query popular products for category=$category" }
            emptyList()
        }
    }

    /**
     * 행동 데이터 집계
     */
    private fun queryBehaviorAggregation(
        category: String?,
        actionTypes: List<String>,
        limit: Int
    ): List<String> {
        val aggResponse = esClient.search({ s ->
            s.index(BEHAVIOR_INDEX)
                .size(0)
                .query { q ->
                    q.bool { b ->
                        // actionTypes 필터 (단일 또는 복수)
                        if (actionTypes.size == 1) {
                            b.must { m ->
                                m.term { t -> t.field("actionType").value(actionTypes.first()) }
                            }
                        } else {
                            b.must { m ->
                                m.terms { t ->
                                    t.field("actionType").terms { tv ->
                                        tv.value(actionTypes.map { FieldValue.of(it) })
                                    }
                                }
                            }
                        }
                        // 최근 7일
                        b.must { m ->
                            m.range { r -> r.field("timestamp").gte(JsonData.of("now-7d")) }
                        }
                        // 카테고리 필터
                        if (category != null) {
                            b.must { m ->
                                m.term { t -> t.field("category").value(category) }
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

        return aggResponse.aggregations()["popular_products"]
            ?.sterms()?.buckets()?.array()
            ?.map { it.key().stringValue() } ?: emptyList()
    }

    /**
     * 최신 등록 상품 조회 (행동 데이터가 없을 때 폴백)
     */
    private fun getLatestProducts(category: String?, limit: Int): List<ProductRecommendation> {
        return try {
            val response = esClient.search({ s ->
                s.index(PRODUCT_INDEX)
                    .size(limit)
                    .query { q ->
                        if (category != null) {
                            q.term { t -> t.field("category").value(category) }
                        } else {
                            q.matchAll { it }
                        }
                    }
                    .sort { sort ->
                        sort.field { f -> f.field("createdAt").order(SortOrder.Desc) }
                    }
                    .source { src ->
                        src.filter { filter ->
                            filter.includes("productId", "productName", "category", "price")
                        }
                    }
            }, ProductDocument::class.java)

            response.hits().hits().mapNotNull { hit ->
                hit.source()?.let { product ->
                    ProductRecommendation(
                        productId = product.productId ?: hit.id() ?: "",
                        productName = product.productName ?: "",
                        category = product.category ?: "",
                        price = product.price ?: 0f,
                        score = 0.0
                    )
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to get latest products for category=$category" }
            emptyList()
        }
    }

    /**
     * 상품 ID 목록으로 상품 상세 정보를 조회합니다.
     */
    private fun getProductDetails(productIds: List<String>): List<ProductRecommendation> {
        if (productIds.isEmpty()) return emptyList()

        return try {
            val response = esClient.mget(
                { m -> m.index(PRODUCT_INDEX).ids(productIds) },
                ProductDocument::class.java
            )

            // 원래 순서 유지 (인기순)
            val productMap = response.docs()
                .filter { it.result()?.found() == true }
                .mapNotNull { doc ->
                    val source = doc.result()?.source()
                    if (source != null) {
                        doc.result()?.id() to ProductRecommendation(
                            productId = source.productId ?: doc.result()?.id() ?: "",
                            productName = source.productName ?: "",
                            category = source.category ?: "",
                            price = source.price ?: 0f,
                            score = 0.0  // 인기 상품은 score 없음
                        )
                    } else null
                }
                .toMap()

            productIds.mapNotNull { productMap[it] }

        } catch (e: Exception) {
            log.error(e) { "Failed to get product details for ${productIds.size} products" }
            emptyList()
        }
    }
}
