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
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * 재고/가격 변동 이벤트 Consumer
 *
 * product.inventory.v1 토픽에서 이벤트를 수신하여
 * 가격 하락, 재입고 등 알림 조건을 감지합니다.
 *
 * @see docs/phase%204.md - Inventory Event Consumer
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

    @KafkaListener(
        topics = ["\${notification.inventory-topic}"],
        groupId = "notification-consumer-group",
        containerFactory = "inventoryListenerContainerFactory"
    )
    fun consume(
        record: ConsumerRecord<String, ProductInventoryEvent>,
        acknowledgment: Acknowledgment
    ) {
        val event = record.value()

        log.debug {
            "Received inventory event: eventId=${event.eventId}, " +
                "type=${event.eventType}, productId=${event.productId}"
        }

        runBlocking(dispatcher) {
            try {
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

                processedCounter.increment()
                acknowledgment.acknowledge()

            } catch (e: Exception) {
                log.error(e) { "Failed to process inventory event: ${event.eventId}" }
                errorCounter.increment()
                // 오류 시에도 커밋하여 무한 재처리 방지
                // 실제 운영에서는 DLQ 전송 고려
                acknowledgment.acknowledge()
            }
        }
    }
}
