package com.rep.recommendation.controller

import com.rep.recommendation.model.RecommendationResponse
import com.rep.recommendation.service.RecommendationService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

/**
 * 추천 API Controller
 *
 * 유저에게 개인화된 상품 추천을 제공합니다.
 *
 * @see docs/phase%203.md - 추천 API 명세
 */
@RestController
@RequestMapping("/api/v1/recommendations")
class RecommendationController(
    private val recommendationService: RecommendationService,
    private val virtualThreadDispatcher: CoroutineDispatcher
) {

    /**
     * 인기 상품을 조회합니다 (Cold Start 또는 비로그인 유저용).
     *
     * GET /api/v1/recommendations/popular
     *
     * Note: 이 메서드는 /{userId} 보다 먼저 정의되어야 합니다.
     * Spring MVC는 더 구체적인 경로를 먼저 매칭하므로,
     * /popular가 {userId}로 잘못 매칭되는 것을 방지합니다.
     */
    @GetMapping("/popular")
    fun getPopularProducts(
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(required = false) category: String?
    ): ResponseEntity<RecommendationResponse> {
        log.debug { "Popular products request: limit=$limit, category=$category" }

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

    /**
     * 헬스체크 엔드포인트
     */
    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("status" to "ok"))
    }

    /**
     * 유저에게 개인화된 상품을 추천합니다.
     *
     * GET /api/v1/recommendations/{userId}
     *
     * Query Parameters:
     * - limit: 추천 개수 (default: 10, max: 50)
     * - category: 카테고리 필터 (optional)
     * - excludeViewed: 이미 본 상품 제외 (default: true)
     *
     * Response:
     * {
     *   "userId": "U12345",
     *   "recommendations": [...],
     *   "strategy": "knn" | "popularity" | "category_best",
     *   "latencyMs": 45
     * }
     */
    @GetMapping("/{userId}")
    fun getRecommendations(
        @PathVariable userId: String,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(required = false) category: String?,
        @RequestParam(defaultValue = "true") excludeViewed: Boolean
    ): ResponseEntity<RecommendationResponse> {
        log.debug { "Recommendation request: userId=$userId, limit=$limit, category=$category" }

        // Virtual Thread dispatcher로 runBlocking 실행
        // Blocking I/O 발생 시에도 Virtual Thread가 unmount되어 처리량 유지
        val response = runBlocking(virtualThreadDispatcher) {
            recommendationService.getRecommendations(
                userId = userId,
                limit = limit.coerceIn(1, 50),
                category = category,
                excludeViewed = excludeViewed
            )
        }

        log.info {
            "Recommendation response: userId=$userId, count=${response.recommendations.size}, " +
                "strategy=${response.strategy}, latencyMs=${response.latencyMs}"
        }

        return ResponseEntity.ok(response)
    }
}
