package com.rep.notification.service

import com.rep.notification.config.NotificationProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

private val log = KotlinLogging.logger {}

/**
 * 알림 중복 및 과다 발송 방지 서비스
 *
 * Redis를 사용하여:
 * 1. 동일 유저+상품+타입에 대해 일정 시간 내 중복 알림 방지
 * 2. 유저별 일일 알림 횟수 제한
 *
 * @see docs/phase%204.md - 알림 중복 방지
 */
@Component
class NotificationRateLimiter(
    private val redisTemplate: ReactiveRedisTemplate<String, String>,
    private val properties: NotificationProperties,
    meterRegistry: MeterRegistry
) {
    companion object {
        private const val SENT_KEY_PREFIX = "notification:sent:"
        private const val DAILY_KEY_PREFIX = "notification:daily:"
    }

    private val duplicateBlockedCounter = Counter.builder("notification.rate.duplicate.blocked")
        .description("Notifications blocked due to duplicate")
        .register(meterRegistry)

    private val dailyLimitBlockedCounter = Counter.builder("notification.rate.daily.blocked")
        .description("Notifications blocked due to daily limit")
        .register(meterRegistry)

    /**
     * 동일 유저 + 동일 상품 + 동일 타입에 대해 중복 알림을 방지합니다.
     *
     * @param userId 유저 ID
     * @param productId 상품 ID
     * @param notificationType 알림 유형 (PRICE_DROP, BACK_IN_STOCK 등)
     * @return true면 발송 가능, false면 중복으로 차단
     */
    suspend fun shouldSend(userId: String, productId: String, notificationType: String): Boolean {
        val key = "$SENT_KEY_PREFIX$userId:$productId:$notificationType"

        return try {
            val exists = redisTemplate.hasKey(key).awaitSingle()
            if (exists) {
                log.debug { "Duplicate notification blocked: userId=$userId, productId=$productId, type=$notificationType" }
                duplicateBlockedCounter.increment()
                return false
            }

            // TTL로 키 설정 (중복 방지 기간)
            redisTemplate.opsForValue()
                .set(key, "1", Duration.ofHours(properties.duplicatePreventionHours))
                .awaitSingle()

            true
        } catch (e: Exception) {
            log.error(e) { "Failed to check duplicate for userId=$userId" }
            // Redis 오류 시에도 발송 허용 (fail-open)
            true
        }
    }

    /**
     * 유저별 일일 알림 횟수 제한을 확인합니다.
     *
     * @param userId 유저 ID
     * @return true면 발송 가능, false면 일일 제한 초과로 차단
     */
    suspend fun checkDailyLimit(userId: String): Boolean {
        val key = "$DAILY_KEY_PREFIX$userId"

        return try {
            val count = redisTemplate.opsForValue()
                .increment(key)
                .awaitSingleOrNull() ?: 1L

            if (count == 1L) {
                // 첫 알림이면 자정까지 TTL 설정
                val ttl = calculateTtlUntilMidnight()
                redisTemplate.expire(key, ttl).awaitSingle()
            }

            if (count > properties.dailyLimitPerUser) {
                log.debug { "Daily limit exceeded for userId=$userId, count=$count" }
                dailyLimitBlockedCounter.increment()
                return false
            }

            true
        } catch (e: Exception) {
            log.error(e) { "Failed to check daily limit for userId=$userId" }
            // Redis 오류 시에도 발송 허용 (fail-open)
            true
        }
    }

    /**
     * 중복 체크와 일일 제한을 모두 확인합니다.
     *
     * @return true면 발송 가능
     */
    suspend fun canSend(userId: String, productId: String, notificationType: String): Boolean {
        return shouldSend(userId, productId, notificationType) && checkDailyLimit(userId)
    }

    /**
     * 자정까지 남은 시간을 계산합니다.
     */
    private fun calculateTtlUntilMidnight(): Duration {
        val now = LocalDateTime.now()
        val midnight = now.toLocalDate().plusDays(1).atTime(LocalTime.MIDNIGHT)
        return Duration.between(now, midnight)
    }
}
