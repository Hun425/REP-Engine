package com.rep.notification.service

import com.rep.event.notification.Channel
import com.rep.event.notification.NotificationEvent
import com.rep.event.notification.NotificationType
import com.rep.event.notification.Priority
import com.rep.event.product.ProductInventoryEvent
import com.rep.notification.config.NotificationProperties
import com.rep.notification.repository.ProductRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * 알림 트리거 조건 감지 서비스
 *
 * 가격 하락, 재입고 등 알림 발송 조건을 감지하고
 * 대상 유저를 추출하여 알림을 발송합니다.
 *
 * @see docs/phase%204.md - Event Detector
 */
@Component
class EventDetector(
    private val targetResolver: TargetResolver,
    private val notificationProducer: NotificationProducer,
    private val rateLimiter: NotificationRateLimiter,
    private val productRepository: ProductRepository,
    private val properties: NotificationProperties,
    meterRegistry: MeterRegistry,
    private val observationRegistry: ObservationRegistry
) {

    private val priceDropDetectedCounter = Counter.builder("notification.event.detected")
        .tag("type", "price_drop")
        .description("Price drop events detected")
        .register(meterRegistry)

    private val restockDetectedCounter = Counter.builder("notification.event.detected")
        .tag("type", "restock")
        .description("Restock events detected")
        .register(meterRegistry)

    private val notificationTriggeredCounter = Counter.builder("notification.triggered")
        .description("Total notifications triggered")
        .register(meterRegistry)

    private val rateLimitedCounter = Counter.builder("notification.rate.limited")
        .description("Notifications blocked by rate limiter")
        .register(meterRegistry)

    /**
     * 가격 하락을 감지합니다.
     * 조건: 가격이 설정된 임계값(기본 10%) 이상 하락한 경우
     *
     * @param event 재고/가격 변동 이벤트
     */
    suspend fun detectPriceDrop(event: ProductInventoryEvent) {
        val previousPrice = event.previousPrice ?: return
        val currentPrice = event.currentPrice ?: return

        if (previousPrice <= 0) return

        val dropPercentage = ((previousPrice - currentPrice) / previousPrice * 100).toInt()

        if (dropPercentage >= properties.priceDropThreshold) {
            val observation = Observation.createNotStarted("notification.detect-price-drop", observationRegistry)
                .lowCardinalityKeyValue("productId", event.productId.toString())
                .lowCardinalityKeyValue("dropPercent", dropPercentage.toString())
            observation.start()

            priceDropDetectedCounter.increment()
            log.info {
                "Price drop detected: productId=${event.productId}, " +
                    "previous=$previousPrice, current=$currentPrice, drop=$dropPercentage%"
            }

            // 해당 상품에 관심 보인 유저 조회
            val targetUsers = targetResolver.findInterestedUsers(
                productId = event.productId.toString(),
                actionTypes = listOf("VIEW", "CLICK", "ADD_TO_CART"),
                withinDays = properties.interestedUserDays
            )

            if (targetUsers.isEmpty()) {
                log.debug { "No interested users found for productId=${event.productId}" }
                return
            }

            // 상품 정보 조회
            val product = productRepository.findById(event.productId.toString())
            val productName = product?.productName ?: "상품"

            // 알림 발송 (배치 처리로 Kafka 버스트 방지)
            var sentCount = 0
            val batches = targetUsers.chunked(properties.batchSize)

            for ((batchIndex, batch) in batches.withIndex()) {
                for (userId in batch) {
                    // Rate Limit 체크
                    if (!rateLimiter.canSend(userId, event.productId.toString(), "PRICE_DROP")) {
                        rateLimitedCounter.increment()
                        continue
                    }

                    val notification = NotificationEvent.newBuilder()
                        .setNotificationId(UUID.randomUUID().toString())
                        .setUserId(userId)
                        .setProductId(event.productId.toString())
                        .setNotificationType(NotificationType.PRICE_DROP)
                        .setTitle("가격이 떨어졌어요!")
                        .setBody("${productName}이(가) ${dropPercentage}% 할인 중입니다!")
                        .setData(
                            mapOf(
                                "previousPrice" to previousPrice.toString(),
                                "currentPrice" to currentPrice.toString(),
                                "dropPercentage" to dropPercentage.toString()
                            )
                        )
                        .setChannels(listOf(Channel.PUSH, Channel.IN_APP))
                        .setPriority(Priority.HIGH)
                        .setTimestamp(Instant.now())
                        .setTraceId(event.traceId?.toString())
                        .build()

                    notificationProducer.send(notification)
                    sentCount++
                    notificationTriggeredCounter.increment()
                }

                // 마지막 배치가 아니면 딜레이 적용 (Kafka 버스트 방지)
                if (batchIndex < batches.size - 1) {
                    delay(properties.batchDelayMs)
                }
            }

            log.info {
                "Price drop notifications sent: productId=${event.productId}, " +
                    "targetUsers=${targetUsers.size}, sent=$sentCount, batches=${batches.size}"
            }

            observation.stop()
        }
    }

    /**
     * 재입고를 감지합니다.
     * 조건: 재고가 0에서 1 이상으로 변경된 경우
     *
     * @param event 재고/가격 변동 이벤트
     */
    suspend fun detectRestock(event: ProductInventoryEvent) {
        val previousStock = event.previousStock ?: return
        val currentStock = event.currentStock ?: return

        if (previousStock == 0 && currentStock > 0) {
            val observation = Observation.createNotStarted("notification.detect-restock", observationRegistry)
                .lowCardinalityKeyValue("productId", event.productId.toString())
            observation.start()

            restockDetectedCounter.increment()
            log.info { "Restock detected: productId=${event.productId}, stock=$currentStock" }

            // 장바구니에 담은 유저 조회
            val targetUsers = targetResolver.findUsersWithCartItem(event.productId.toString())

            if (targetUsers.isEmpty()) {
                log.debug { "No cart users found for productId=${event.productId}" }
                return
            }

            // 상품 정보 조회
            val product = productRepository.findById(event.productId.toString())
            val productName = product?.productName ?: "상품"

            // 알림 발송 (배치 처리로 Kafka 버스트 방지)
            var sentCount = 0
            val batches = targetUsers.chunked(properties.batchSize)

            for ((batchIndex, batch) in batches.withIndex()) {
                for (userId in batch) {
                    // Rate Limit 체크
                    if (!rateLimiter.canSend(userId, event.productId.toString(), "BACK_IN_STOCK")) {
                        rateLimitedCounter.increment()
                        continue
                    }

                    val notification = NotificationEvent.newBuilder()
                        .setNotificationId(UUID.randomUUID().toString())
                        .setUserId(userId)
                        .setProductId(event.productId.toString())
                        .setNotificationType(NotificationType.BACK_IN_STOCK)
                        .setTitle("재입고 알림")
                        .setBody("${productName}이(가) 다시 입고되었습니다!")
                        .setData(mapOf("currentStock" to currentStock.toString()))
                        .setChannels(listOf(Channel.PUSH, Channel.SMS))
                        .setPriority(Priority.HIGH)
                        .setTimestamp(Instant.now())
                        .setTraceId(event.traceId?.toString())
                        .build()

                    notificationProducer.send(notification)
                    sentCount++
                    notificationTriggeredCounter.increment()
                }

                // 마지막 배치가 아니면 딜레이 적용 (Kafka 버스트 방지)
                if (batchIndex < batches.size - 1) {
                    delay(properties.batchDelayMs)
                }
            }

            log.info {
                "Restock notifications sent: productId=${event.productId}, " +
                    "targetUsers=${targetUsers.size}, sent=$sentCount, batches=${batches.size}"
            }

            observation.stop()
        }
    }
}
