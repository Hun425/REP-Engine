package com.rep.notification.client

import com.rep.notification.config.NotificationProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import mu.KotlinLogging
import io.netty.channel.ChannelOption
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import reactor.netty.http.client.HttpClient
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * 추천 API HTTP 클라이언트
 *
 * recommendation-api 서비스에서 유저별 추천 상품을 조회합니다.
 * WebClient 기반 비동기 호출 + 타임아웃/에러 처리
 *
 * @see RecommendationScheduler
 */
@Component
class RecommendationClient(
    private val properties: NotificationProperties,
    meterRegistry: MeterRegistry
) {
    private val webClient: WebClient = WebClient.builder()
        .baseUrl(properties.recommendation.apiUrl)
        .clientConnector(ReactorClientHttpConnector(
            HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(10))
        ))
        .build()

    private val requestCounter = Counter.builder("notification.recommendation_client.request")
        .description("Recommendation API requests")
        .register(meterRegistry)

    private val successCounter = Counter.builder("notification.recommendation_client.success")
        .description("Successful recommendation API requests")
        .register(meterRegistry)

    private val failureCounter = Counter.builder("notification.recommendation_client.failure")
        .description("Failed recommendation API requests")
        .register(meterRegistry)

    private val latencyTimer = Timer.builder("notification.recommendation_client.latency")
        .description("Recommendation API latency")
        .register(meterRegistry)

    /**
     * 유저별 추천 상품을 조회합니다.
     *
     * @param userId 유저 ID
     * @param limit 추천 상품 개수 (기본값: 설정에서 읽음)
     * @return 추천 응답 또는 null (실패 시)
     */
    suspend fun getRecommendations(
        userId: String,
        limit: Int = properties.recommendation.limit
    ): RecommendationResponse? {
        requestCounter.increment()

        return try {
            val sample = Timer.start()

            val response = webClient.get()
                .uri("/api/v1/recommendations/{userId}?limit={limit}", userId, limit)
                .retrieve()
                .awaitBodyOrNull<RecommendationResponse>()

            sample.stop(latencyTimer)

            if (response != null) {
                successCounter.increment()
                log.debug {
                    "Got ${response.recommendations.size} recommendations for userId=$userId, " +
                        "strategy=${response.strategy}"
                }
            }

            response

        } catch (e: Exception) {
            failureCounter.increment()
            log.warn(e) { "Failed to get recommendations for userId=$userId" }
            null
        }
    }
}

/**
 * 추천 API 응답 DTO
 *
 * recommendation-api의 RecommendationResponse와 동일한 구조
 */
data class RecommendationResponse(
    val userId: String,
    val recommendations: List<ProductRecommendation>,
    val strategy: String,
    val latencyMs: Long
)

/**
 * 추천 상품 정보 DTO
 */
data class ProductRecommendation(
    val productId: String,
    val productName: String,
    val category: String,
    val price: Float,
    val score: Double = 0.0
)
