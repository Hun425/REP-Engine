package com.rep.recommendation.service

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.json.JsonData
import com.rep.recommendation.config.RecommendationProperties
import com.rep.recommendation.model.ProductDocument
import com.rep.recommendation.model.ProductRecommendation
import com.rep.recommendation.model.RecommendationResponse
import com.rep.recommendation.repository.UserBehaviorRepository
import com.rep.recommendation.repository.UserPreferenceRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * 추천 서비스
 *
 * 유저의 취향 벡터를 기반으로 KNN 검색을 수행하여 개인화된 상품을 추천합니다.
 * Cold Start 유저에게는 인기 상품을 추천합니다.
 *
 * @see docs/phase%203.md
 */
@Service
class RecommendationService(
    private val userPreferenceRepository: UserPreferenceRepository,
    private val userBehaviorRepository: UserBehaviorRepository,
    private val popularProductsCache: PopularProductsCache,
    private val esClient: ElasticsearchClient,
    private val properties: RecommendationProperties,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        private const val PRODUCT_INDEX = "product_index"
    }

    private val latencyTimer = Timer.builder("recommendation.latency")
        .description("Recommendation API latency")
        .register(meterRegistry)

    private val knnCounter = Counter.builder("recommendation.strategy")
        .tag("type", "knn")
        .register(meterRegistry)

    private val popularityCounter = Counter.builder("recommendation.strategy")
        .tag("type", "popularity")
        .register(meterRegistry)

    /**
     * 유저에게 개인화된 상품을 추천합니다.
     *
     * @param userId 유저 ID
     * @param limit 추천 개수 (기본: 10, 최대: 50)
     * @param category 카테고리 필터 (optional)
     * @param excludeViewed 이미 본 상품 제외 여부 (기본: true)
     * @return 추천 응답
     */
    suspend fun getRecommendations(
        userId: String,
        limit: Int = 10,
        category: String? = null,
        excludeViewed: Boolean = true
    ): RecommendationResponse {
        val startTime = System.nanoTime()

        return try {
            val result = executeRecommendation(userId, limit.coerceAtMost(50), category, excludeViewed)
            val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)

            latencyTimer.record(latencyMs, TimeUnit.MILLISECONDS)

            result.copy(latencyMs = latencyMs)

        } catch (e: Exception) {
            log.error(e) { "Failed to get recommendations for userId=$userId" }

            // 에러 발생 시에도 인기 상품이라도 반환
            val fallbackProducts = try {
                popularProductsCache.getTopProducts(limit)
            } catch (e2: Exception) {
                emptyList()
            }

            RecommendationResponse(
                userId = userId,
                recommendations = fallbackProducts,
                strategy = "fallback",
                latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
            )
        }
    }

    private suspend fun executeRecommendation(
        userId: String,
        limit: Int,
        category: String?,
        excludeViewed: Boolean
    ): RecommendationResponse {
        // 1. 유저 취향 벡터 조회
        val preferenceVector = userPreferenceRepository.get(userId)

        // 2. 전략 결정 및 추천 실행
        val (products, strategy) = if (preferenceVector == null) {
            // Cold Start: 인기 상품 반환
            log.debug { "Cold start for userId=$userId, using popularity strategy" }
            popularityCounter.increment()
            Pair(getColdStartRecommendations(limit, category), "popularity")
        } else {
            // KNN 검색
            log.debug { "Using KNN strategy for userId=$userId" }
            knnCounter.increment()
            val excludeIds = if (excludeViewed) {
                userBehaviorRepository.getRecentViewedProducts(userId, 100)
            } else emptyList()

            Pair(
                searchSimilarProducts(preferenceVector, limit, category, excludeIds),
                "knn"
            )
        }

        return RecommendationResponse(
            userId = userId,
            recommendations = products,
            strategy = strategy,
            latencyMs = 0  // 나중에 설정됨
        )
    }

    /**
     * KNN 검색으로 유사 상품을 조회합니다.
     */
    private fun searchSimilarProducts(
        queryVector: FloatArray,
        k: Int,
        category: String?,
        excludeIds: List<String>
    ): List<ProductRecommendation> {
        return try {
            // 필터 쿼리 생성
            val filterQueries = mutableListOf<Query>()

            // 카테고리 필터
            if (category != null) {
                filterQueries.add(Query.of { q ->
                    q.term { t -> t.field("category").value(category) }
                })
            }

            // 이미 본 상품 제외
            if (excludeIds.isNotEmpty()) {
                filterQueries.add(Query.of { q ->
                    q.bool { b ->
                        b.mustNot { mn -> mn.ids { ids -> ids.values(excludeIds) } }
                    }
                })
            }

            // 재고 있는 상품만
            filterQueries.add(Query.of { q ->
                q.range { r -> r.field("stock").gt(JsonData.of(0)) }
            })

            // queryVector를 List<Float>로 변환
            val queryVectorList: List<Float> = queryVector.toList()

            val response = esClient.search({ s ->
                s.index(PRODUCT_INDEX)
                    .knn { knn ->
                        knn.field("productVector")
                            .queryVector(queryVectorList)
                            .k(k.toLong())
                            .numCandidates(properties.knn.numCandidates.toLong())
                            .filter(filterQueries)
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
                        score = hit.score() ?: 0.0
                    )
                }
            }

        } catch (e: Exception) {
            log.error(e) { "KNN search failed" }
            emptyList()
        }
    }

    /**
     * Cold Start 유저를 위한 추천을 조회합니다.
     */
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
