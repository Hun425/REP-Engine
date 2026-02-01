package com.rep.notification.consumer

import com.rep.event.product.InventoryEventType
import com.rep.event.product.ProductInventoryEvent
import com.rep.notification.service.EventDetector
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * 재고/가격 변동 이벤트 Consumer
 *
 * product.inventory.v1 토픽에서 이벤트를 수신하여
 * 가격 하락, 재입고 등 알림 조건을 감지합니다.
 *
 * 에러 처리:
 * - DefaultErrorHandler가 재시도 및 DLQ 전송 담당
 * - 예외 발생 시 에러 핸들러로 전파하여 재시도/DLQ 처리
 *
 * @see docs/phase%204.md - Inventory Event Consumer
 * @see KafkaConsumerConfig - DLQ 설정
 */
@Component
@OptIn(ExperimentalCoroutinesApi::class)
class InventoryEventConsumer(
    private val eventDetector: EventDetector,
    private val meterRegistry: MeterRegistry,
    @param:Qualifier("virtualThreadDispatcher") private val dispatcher: CloseableCoroutineDispatcher
) {
    private val processedCounter = Counter.builder("inventory.events.processed")
        .description("Inventory events processed")
        .register(meterRegistry)

    private val errorCounter = Counter.builder("inventory.events.error")
        .description("Inventory event processing errors")
        .register(meterRegistry)

    /**
     * 재고/가격 변동 이벤트 처리
     *
     * 예외 발생 시 에러 핸들러가 처리:
     * 1. 설정된 횟수만큼 재시도 (기본 3회)
     * 2. 재시도 실패 시 DLQ로 전송 (product.inventory.v1.dlq)
     */
    @KafkaListener(
        topics = ["\${notification.inventory-topic}"],
        groupId = "notification-consumer-group",
        containerFactory = "inventoryListenerContainerFactory"
    )
    fun consume(record: ConsumerRecord<String, ProductInventoryEvent>) {
        val event = record.value()

        log.debug {
            "Received inventory event: eventId=${event.eventId}, " +
                "type=${event.eventType}, productId=${event.productId}"
        }

        try {
            runBlocking(dispatcher) {
                when (event.eventType) {
                    InventoryEventType.PRICE_CHANGE -> {
                        eventDetector.detectPriceDrop(event)
                    }
                    InventoryEventType.STOCK_CHANGE -> {
                        eventDetector.detectRestock(event)
                    }
                    else -> {
                        log.debug { "Ignoring event type: ${event.eventType}" }
                    }
                }
            }

            processedCounter.increment()

        } catch (e: Exception) {
            errorCounter.increment()
            log.error(e) { "Failed to process inventory event: ${event.eventId}" }
            // 예외를 다시 던져서 에러 핸들러가 재시도/DLQ 처리하도록 함
            throw e
        }
    }
}
