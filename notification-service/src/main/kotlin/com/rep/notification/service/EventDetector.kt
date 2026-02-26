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
 * @see docs/phase 4.md - Event Detector
 */
@Component
class EventDetector(
    private val targetResolver: TargetResolver,
    private val notificationProducer: NotificationProducer,
    private val rateLimiter: NotificationRateLimiter,
    private val productRepository: ProductRepository,
    private val properties: NotificationProperties,
    meterRegistry: MeterRegistry,
    private val observationRegistry: ObservationRegistry,
) {
    private val priceDropDetectedCounter =
        Counter
            .builder("notification.event.detected")
            .tag("type", "price_drop")
            .description("Price drop events detected")
            .register(meterRegistry)

    private val restockDetectedCounter =
        Counter
            .builder("notification.event.detected")
            .tag("type", "restock")
            .description("Restock events detected")
            .register(meterRegistry)

    private val notificationTriggeredCounter =
        Counter
            .builder("notification.triggered")
            .description("Total notifications triggered")
            .register(meterRegistry)

    private val rateLimitedCounter =
        Counter
            .builder("notification.rate.limited")
            .description("Notifications blocked by rate limiter")
            .register(meterRegistry)

    /**
     * 가격 하락을 감지합니다.
     * 조건: 가격이 설정된 임계값(기본 10%) 이상 하락한 경우
     *
     * @param event 재고/가격 변동 이벤트
     */
    suspend fun detectPriceDrop(event: ProductInventoryEvent) {
        val priceChange = extractPriceDrop(event) ?: return
        if (priceChange.dropPercentage < properties.priceDropThreshold) return

        withObservation(
            name = "notification.detect-price-drop",
            productId = priceChange.productId,
            extraKey = "dropPercent",
            extraValue = priceChange.dropPercentage.toString(),
        ) {
            priceDropDetectedCounter.increment()
            log.info {
                "Price drop detected: productId=${priceChange.productId}, " +
                    "previous=${priceChange.previousPrice}, current=${priceChange.currentPrice}, " +
                    "drop=${priceChange.dropPercentage}%"
            }

            val targetUsers =
                targetResolver.findInterestedUsers(
                    productId = priceChange.productId,
                    actionTypes = listOf("VIEW", "CLICK", "ADD_TO_CART"),
                    withinDays = properties.interestedUserDays,
                )

            if (targetUsers.isEmpty()) {
                log.debug { "No interested users found for productId=${priceChange.productId}" }
                return@withObservation
            }

            val productName = resolveProductName(priceChange.productId)
            val sentCount =
                sendNotificationsInBatches(
                    targetUsers = targetUsers,
                    productId = priceChange.productId,
                    rateLimitType = "PRICE_DROP",
                ) { userId ->
                    buildPriceDropNotification(
                        userId = userId,
                        productId = priceChange.productId,
                        productName = productName,
                        previousPrice = priceChange.previousPrice,
                        currentPrice = priceChange.currentPrice,
                        dropPercentage = priceChange.dropPercentage,
                        traceId = event.traceId?.toString(),
                    )
                }

            log.info {
                "Price drop notifications sent: productId=${priceChange.productId}, " +
                    "targetUsers=${targetUsers.size}, sent=${sentCount.sentCount}, batches=${sentCount.batchCount}"
            }
        }
    }

    /**
     * 재입고를 감지합니다.
     * 조건: 재고가 0에서 1 이상으로 변경된 경우
     *
     * @param event 재고/가격 변동 이벤트
     */
    suspend fun detectRestock(event: ProductInventoryEvent) {
        val restock = extractRestock(event) ?: return

        withObservation(name = "notification.detect-restock", productId = restock.productId) {
            restockDetectedCounter.increment()
            log.info { "Restock detected: productId=${restock.productId}, stock=${restock.currentStock}" }

            val targetUsers = targetResolver.findUsersWithCartItem(restock.productId)
            if (targetUsers.isEmpty()) {
                log.debug { "No cart users found for productId=${restock.productId}" }
                return@withObservation
            }

            val productName = resolveProductName(restock.productId)
            val sentCount =
                sendNotificationsInBatches(
                    targetUsers = targetUsers,
                    productId = restock.productId,
                    rateLimitType = "BACK_IN_STOCK",
                ) { userId ->
                    buildRestockNotification(
                        userId = userId,
                        productId = restock.productId,
                        productName = productName,
                        currentStock = restock.currentStock,
                        traceId = event.traceId?.toString(),
                    )
                }

            log.info {
                "Restock notifications sent: productId=${restock.productId}, " +
                    "targetUsers=${targetUsers.size}, sent=${sentCount.sentCount}, batches=${sentCount.batchCount}"
            }
        }
    }

    private suspend fun sendNotificationsInBatches(
        targetUsers: List<String>,
        productId: String,
        rateLimitType: String,
        buildNotification: (String) -> NotificationEvent,
    ): BatchSendResult {
        val batches = targetUsers.chunked(properties.batchSize)
        var sentCount = 0

        for ((batchIndex, batch) in batches.withIndex()) {
            for (userId in batch) {
                if (!rateLimiter.canSend(userId, productId, rateLimitType)) {
                    rateLimitedCounter.increment()
                    continue
                }

                notificationProducer.send(buildNotification(userId))
                sentCount++
                notificationTriggeredCounter.increment()
            }

            if (batchIndex < batches.size - 1) {
                delay(properties.batchDelayMs)
            }
        }

        return BatchSendResult(sentCount = sentCount, batchCount = batches.size)
    }

    private fun buildPriceDropNotification(
        userId: String,
        productId: String,
        productName: String,
        previousPrice: Float,
        currentPrice: Float,
        dropPercentage: Int,
        traceId: String?,
    ): NotificationEvent =
        NotificationEvent
            .newBuilder()
            .setNotificationId(UUID.randomUUID().toString())
            .setUserId(userId)
            .setProductId(productId)
            .setNotificationType(NotificationType.PRICE_DROP)
            .setTitle("가격이 떨어졌어요!")
            .setBody("${productName}이(가) $dropPercentage% 할인 중입니다!")
            .setData(
                mapOf(
                    "previousPrice" to previousPrice.toString(),
                    "currentPrice" to currentPrice.toString(),
                    "dropPercentage" to dropPercentage.toString(),
                ),
            ).setChannels(listOf(Channel.PUSH, Channel.IN_APP))
            .setPriority(Priority.HIGH)
            .setTimestamp(Instant.now())
            .setTraceId(traceId)
            .build()

    private fun buildRestockNotification(
        userId: String,
        productId: String,
        productName: String,
        currentStock: Int,
        traceId: String?,
    ): NotificationEvent =
        NotificationEvent
            .newBuilder()
            .setNotificationId(UUID.randomUUID().toString())
            .setUserId(userId)
            .setProductId(productId)
            .setNotificationType(NotificationType.BACK_IN_STOCK)
            .setTitle("재입고 알림")
            .setBody("${productName}이(가) 다시 입고되었습니다!")
            .setData(mapOf("currentStock" to currentStock.toString()))
            .setChannels(listOf(Channel.PUSH, Channel.SMS))
            .setPriority(Priority.HIGH)
            .setTimestamp(Instant.now())
            .setTraceId(traceId)
            .build()

    private fun resolveProductName(productId: String): String = productRepository.findById(productId)?.productName ?: "상품"

    private suspend fun withObservation(
        name: String,
        productId: String,
        extraKey: String? = null,
        extraValue: String? = null,
        block: suspend () -> Unit,
    ) {
        val observation =
            Observation
                .createNotStarted(name, observationRegistry)
                .lowCardinalityKeyValue("productId", productId)
        if (extraKey != null && extraValue != null) {
            observation.lowCardinalityKeyValue(extraKey, extraValue)
        }
        observation.start()
        try {
            block()
        } finally {
            observation.stop()
        }
    }

    private fun extractPriceDrop(event: ProductInventoryEvent): PriceDropChange? {
        val previousPrice = event.previousPrice ?: return null
        val currentPrice = event.currentPrice ?: return null
        if (previousPrice <= 0 || currentPrice < 0) return null

        val dropPercentage = ((previousPrice - currentPrice) / previousPrice * 100).toInt()
        return PriceDropChange(
            productId = event.productId.toString(),
            previousPrice = previousPrice,
            currentPrice = currentPrice,
            dropPercentage = dropPercentage,
        )
    }

    private fun extractRestock(event: ProductInventoryEvent): RestockChange? {
        val previousStock = event.previousStock ?: return null
        val currentStock = event.currentStock ?: return null
        if (previousStock != 0 || currentStock <= 0) return null

        return RestockChange(
            productId = event.productId.toString(),
            currentStock = currentStock,
        )
    }

    private data class PriceDropChange(
        val productId: String,
        val previousPrice: Float,
        val currentPrice: Float,
        val dropPercentage: Int,
    )

    private data class RestockChange(
        val productId: String,
        val currentStock: Int,
    )

    private data class BatchSendResult(
        val sentCount: Int,
        val batchCount: Int,
    )
}
