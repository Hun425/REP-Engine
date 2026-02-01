package com.rep.notification.scheduler

import com.rep.event.notification.Channel
import com.rep.event.notification.NotificationEvent
import com.rep.event.notification.NotificationType
import com.rep.event.notification.Priority
import com.rep.notification.client.RecommendationClient
import com.rep.notification.config.NotificationProperties
import com.rep.notification.repository.ActiveUserRepository
import com.rep.notification.service.NotificationProducer
import com.rep.notification.service.NotificationRateLimiter
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * 추천 알림 배치 스케줄러
 *
 * 매일 설정된 시간에 활성 유저에게 개인화 추천 상품 알림을 발송합니다.
 *
 * 기능:
 * - ShedLock: Redis 분산 락으로 다중 인스턴스 환경에서 단일 실행 보장
 * - 배치 처리: Kafka 버스트 방지를 위한 청크 단위 발송
 * - Rate Limiting: 유저별 일일 알림 한도 준수
 *
 * @see docs/phase%204.md - RECOMMENDATION 알림
 */
@Component
@ConditionalOnProperty(
    prefix = "notification.recommendation",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationScheduler(
    private val activeUserRepository: ActiveUserRepository,
    private val recommendationClient: RecommendationClient,
    private val notificationProducer: NotificationProducer,
    private val rateLimiter: NotificationRateLimiter,
    private val properties: NotificationProperties,
    @param:Qualifier("virtualThreadDispatcher")
    private val dispatcher: CloseableCoroutineDispatcher,
    meterRegistry: MeterRegistry
) {
    // Metrics
    private val batchStartedCounter = Counter.builder("batch.recommendation.started")
        .description("Recommendation batch started count")
        .register(meterRegistry)

    private val batchCompletedCounter = Counter.builder("batch.recommendation.completed")
        .description("Recommendation batch completed count")
        .register(meterRegistry)

    private val batchFailedCounter = Counter.builder("batch.recommendation.failed")
        .description("Recommendation batch failed count")
        .register(meterRegistry)

    private val notificationSentCounter = Counter.builder("batch.recommendation.sent")
        .description("Recommendation notifications sent")
        .register(meterRegistry)

    private val rateLimitedCounter = Counter.builder("batch.recommendation.rate_limited")
        .description("Users skipped due to rate limiting")
        .register(meterRegistry)

    private val noRecommendationCounter = Counter.builder("batch.recommendation.no_result")
        .description("Users with no recommendations")
        .register(meterRegistry)

    private val batchDurationTimer = Timer.builder("batch.recommendation.duration")
        .description("Recommendation batch duration")
        .register(meterRegistry)

    /**
     * 매일 지정 시간에 활성 유저에게 추천 알림 발송
     *
     * @SchedulerLock 설정:
     * - lockAtMostFor: 최대 1시간 락 유지 (장애 시 자동 해제)
     * - lockAtLeastFor: 최소 5분 락 유지 (빠른 완료 시에도 재실행 방지)
     */
    @Scheduled(cron = "\${notification.recommendation.cron:0 0 9 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(
        name = "dailyRecommendationBatch",
        lockAtMostFor = "1h",
        lockAtLeastFor = "5m"
    )
    fun sendDailyRecommendations() {
        val sample = Timer.start()
        batchStartedCounter.increment()

        log.info { "Starting daily recommendation batch job" }

        runBlocking(dispatcher) {
            try {
                executeBatch()
                batchCompletedCounter.increment()
            } catch (e: Exception) {
                batchFailedCounter.increment()
                log.error(e) { "Daily recommendation batch failed" }
            } finally {
                sample.stop(batchDurationTimer)
            }
        }
    }

    /**
     * 배치 로직 실행
     */
    private suspend fun executeBatch() {
        // 1. 활성 유저 조회
        val activeUsers = activeUserRepository.getActiveUsers(
            properties.recommendation.activeUserDays
        )

        if (activeUsers.isEmpty()) {
            log.info { "No active users found, skipping batch" }
            return
        }

        log.info { "Found ${activeUsers.size} active users for recommendation" }

        // 2. 배치 처리
        var sentCount = 0
        var rateLimitedCount = 0
        var noResultCount = 0

        val batches = activeUsers.chunked(properties.batchSize)

        for ((batchIndex, batch) in batches.withIndex()) {
            for (userId in batch) {
                // Rate limit 체크 (DAILY + RECOMMENDATION 조합)
                if (!rateLimiter.canSend(userId, "DAILY_RECOMMENDATION", "RECOMMENDATION")) {
                    rateLimitedCount++
                    rateLimitedCounter.increment()
                    continue
                }

                // 추천 상품 조회
                val recommendations = recommendationClient.getRecommendations(
                    userId = userId,
                    limit = properties.recommendation.limit
                )

                if (recommendations == null || recommendations.recommendations.isEmpty()) {
                    noResultCount++
                    noRecommendationCounter.increment()
                    continue
                }

                // 알림 메시지 생성 및 발송
                val notification = createNotification(userId, recommendations)
                notificationProducer.send(notification)
                sentCount++
                notificationSentCounter.increment()
            }

            // 마지막 배치가 아니면 딜레이 적용 (Kafka 버스트 방지)
            if (batchIndex < batches.size - 1) {
                delay(properties.batchDelayMs)
            }
        }

        log.info {
            "Daily recommendation batch completed: " +
                "totalUsers=${activeUsers.size}, sent=$sentCount, " +
                "rateLimited=$rateLimitedCount, noResult=$noResultCount, " +
                "batches=${batches.size}"
        }
    }

    /**
     * 추천 알림 메시지 생성
     */
    private fun createNotification(
        userId: String,
        recommendations: com.rep.notification.client.RecommendationResponse
    ): NotificationEvent {
        val products = recommendations.recommendations
        val productNames = products.joinToString(", ") { it.productName }
        val firstProductId = products.firstOrNull()?.productId ?: "unknown"

        return NotificationEvent.newBuilder()
            .setNotificationId(UUID.randomUUID().toString())
            .setUserId(userId)
            .setProductId(firstProductId)  // 대표 상품 ID
            .setNotificationType(NotificationType.RECOMMENDATION)
            .setTitle("오늘의 추천 상품")
            .setBody("${productNames}을(를) 추천드려요!")
            .setData(
                mapOf(
                    "strategy" to recommendations.strategy,
                    "productCount" to products.size.toString(),
                    "productIds" to products.joinToString(",") { it.productId }
                )
            )
            .setChannels(listOf(Channel.PUSH, Channel.IN_APP))
            .setPriority(Priority.NORMAL)
            .setTimestamp(Instant.now())
            .build()
    }
}
