package com.rep.recommendation.service

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.JsonData
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rep.recommendation.config.RecommendationProperties
import com.rep.recommendation.model.ProductDocument
import com.rep.recommendation.model.ProductRecommendation
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
    private val properties: RecommendationProperties
) {
    companion object {
        private const val CACHE_KEY_GLOBAL = "popular:global"
        private const val CACHE_KEY_CATEGORY_PREFIX = "popular:category:"
        private const val PRODUCT_INDEX = "product_index"
        private const val BEHAVIOR_INDEX = "user_behavior_index"
    }

    private val cacheTtl = Duration.ofMinutes(properties.cache.popularTtlMinutes)

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
            return objectMapper.readValue<List<ProductRecommendation>>(cached).take(limit)
        }

        // 2. ES에서 조회
        log.debug { "Cache miss for global popular products, querying ES" }
        val products = queryPopularProducts(category = null, limit = 100)

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
            return objectMapper.readValue<List<ProductRecommendation>>(cached).take(limit)
        }

        // 2. ES에서 조회
        log.debug { "Cache miss for category=$category popular products, querying ES" }
        val products = queryPopularProducts(category = category, limit = 50)

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
     * 최근 7일 PURCHASE 집계 기준으로 인기 상품을 선정합니다.
     */
    private fun queryPopularProducts(category: String?, limit: Int): List<ProductRecommendation> {
        return try {
            // 1. user_behavior_index에서 PURCHASE 집계
            val aggResponse = esClient.search({ s ->
                s.index(BEHAVIOR_INDEX)
                    .size(0)
                    .query { q ->
                        q.bool { b ->
                            b.must { m ->
                                m.term { t -> t.field("actionType").value("PURCHASE") }
                            }
                            b.must { m ->
                                m.range { r -> r.field("timestamp").gte(JsonData.of("now-7d")) }
                            }
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

            val productIds = aggResponse.aggregations()["popular_products"]
                ?.sterms()?.buckets()?.array()
                ?.map { it.key().stringValue() } ?: emptyList()

            if (productIds.isEmpty()) {
                log.debug { "No popular products found for category=$category" }
                return emptyList()
            }

            // 2. 상품 상세 정보 조회
            getProductDetails(productIds)

        } catch (e: Exception) {
            log.error(e) { "Failed to query popular products for category=$category" }
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
