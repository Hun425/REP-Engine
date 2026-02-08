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
import java.time.ZoneId
import java.time.ZonedDateTime

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

    private val failCloseBlockedCounter = Counter.builder("notification.rate.fail_close.blocked")
        .description("Notifications blocked due to Redis failure (fail-close policy)")
        .register(meterRegistry)

    /**
     * 동일 유저 + 동일 상품 + 동일 타입에 대해 중복 알림을 방지합니다.
     *
     * SETNX (setIfAbsent) 사용으로 원자적 연산을 보장합니다.
     * - 키가 없으면: 키 설정 + true 반환 (발송 허용)
     * - 키가 있으면: false 반환 (중복 차단)
     *
     * @param userId 유저 ID
     * @param productId 상품 ID
     * @param notificationType 알림 유형 (PRICE_DROP, BACK_IN_STOCK 등)
     * @return true면 발송 가능, false면 중복으로 차단
     */
    suspend fun shouldSend(userId: String, productId: String, notificationType: String): Boolean {
        val key = "$SENT_KEY_PREFIX$userId:$productId:$notificationType"

        return try {
            // 원자적 SETNX: 키가 없으면 설정하고 true, 있으면 false
            val wasSet = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofHours(properties.duplicatePreventionHours))
                .awaitSingleOrNull() ?: false

            if (!wasSet) {
                log.debug { "Duplicate notification blocked: userId=$userId, productId=$productId, type=$notificationType" }
                duplicateBlockedCounter.increment()
            }

            wasSet
        } catch (e: Exception) {
            log.error(e) { "Failed to check duplicate for userId=$userId (blocking - fail-close policy)" }
            failCloseBlockedCounter.increment()
            // Redis 오류 시 발송 차단 (fail-close)
            false
        }
    }

    /**
     * 유저별 일일 알림 횟수 제한을 확인합니다.
     *
     * TTL Race 방지: setIfAbsent로 TTL과 함께 키 생성을 보장한 후 increment.
     * 기존 방식(increment 후 expire)은 두 연산 사이 장애 시 TTL 없는 키가 남을 수 있음.
     *
     * @param userId 유저 ID
     * @return true면 발송 가능, false면 일일 제한 초과로 차단
     */
    suspend fun checkDailyLimit(userId: String): Boolean {
        val key = "$DAILY_KEY_PREFIX$userId"

        return try {
            val ttl = calculateTtlUntilMidnight()

            // 1. 키가 없으면 TTL과 함께 생성 (원자적)
            //    키가 이미 있으면 무시됨 (기존 TTL 유지)
            redisTemplate.opsForValue()
                .setIfAbsent(key, "0", ttl)
                .awaitSingleOrNull()

            // 2. 카운트 증가 (항상 TTL이 설정된 상태에서 실행됨)
            val count = redisTemplate.opsForValue()
                .increment(key)
                .awaitSingleOrNull() ?: 1L

            if (count > properties.dailyLimitPerUser) {
                log.debug { "Daily limit exceeded for userId=$userId, count=$count" }
                dailyLimitBlockedCounter.increment()
                return false
            }

            true
        } catch (e: Exception) {
            log.error(e) { "Failed to check daily limit for userId=$userId (blocking - fail-close policy)" }
            failCloseBlockedCounter.increment()
            // Redis 오류 시 발송 차단 (fail-close)
            false
        }
    }

    /**
     * 중복 체크와 일일 제한을 모두 확인합니다.
     *
     * @return true면 발송 가능
     */
    suspend fun canSend(userId: String, productId: String, notificationType: String): Boolean {
        return checkDailyLimit(userId) && shouldSend(userId, productId, notificationType)
    }

    /**
     * 자정까지 남은 시간을 계산합니다.
     */
    private fun calculateTtlUntilMidnight(): Duration {
        val seoulZone = ZoneId.of("Asia/Seoul")
        val now = ZonedDateTime.now(seoulZone)
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(seoulZone)
        return Duration.between(now, midnight)
    }
}
