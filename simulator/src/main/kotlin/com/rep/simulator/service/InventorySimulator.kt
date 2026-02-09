package com.rep.simulator.service

import com.rep.event.product.ProductInventoryEvent
import com.rep.simulator.config.SimulatorProperties
import com.rep.simulator.domain.ProductCatalog
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private val log = KotlinLogging.logger {}

/**
 * 인벤토리 이벤트 시뮬레이터
 *
 * 주기적으로 상품의 가격/재고 변동 이벤트를 생성하여
 * product.inventory.v1 토픽으로 전송합니다.
 * Notification Service의 EventDetector가 이 이벤트를 소비합니다.
 */
@Service
class InventorySimulator(
    private val inventoryKafkaTemplate: KafkaTemplate<String, ProductInventoryEvent>,
    private val properties: SimulatorProperties,
    private val meterRegistry: MeterRegistry
) {
    private val virtualThreadDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(virtualThreadDispatcher + SupervisorJob())
    private var simulationJob: Job? = null

    private val isRunning = AtomicBoolean(false)
    private val totalEventsSent = AtomicLong(0)

    private val catalog = ProductCatalog(properties.productCountPerCategory)

    private val sentCounter = Counter.builder("simulator.inventory.events.sent")
        .tag("topic", properties.inventoryTopic)
        .register(meterRegistry)

    private val failedCounter = Counter.builder("simulator.inventory.events.failed")
        .tag("topic", properties.inventoryTopic)
        .register(meterRegistry)

    private val priceChangeCounter = Counter.builder("simulator.inventory.events.type")
        .tag("type", "price_change")
        .register(meterRegistry)

    private val restockCounter = Counter.builder("simulator.inventory.events.type")
        .tag("type", "restock")
        .register(meterRegistry)

    fun startSimulation() {
        if (isRunning.compareAndSet(false, true)) {
            log.info { "Starting inventory simulation, interval=${properties.inventoryIntervalMs}ms" }

            simulationJob = scope.launch {
                while (currentCoroutineContext().isActive) {
                    try {
                        val event = generateEvent()
                        sendToKafka(event)
                        delay(properties.inventoryIntervalMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error(e) { "Error generating inventory event" }
                        delay(1000)
                    }
                }
            }
        } else {
            log.warn { "Inventory simulation is already running" }
        }
    }

    private fun generateEvent(): ProductInventoryEvent {
        return if (Random.nextDouble() < 0.7) {
            priceChangeCounter.increment()
            catalog.generatePriceChange()
        } else {
            restockCounter.increment()
            catalog.generateRestock()
        }
    }

    private fun sendToKafka(event: ProductInventoryEvent) {
        val future = inventoryKafkaTemplate.send(
            properties.inventoryTopic,
            event.productId.toString(),
            event
        )

        future.whenComplete { result, ex ->
            if (ex == null) {
                sentCounter.increment()
                val count = totalEventsSent.incrementAndGet()
                if (count % 100 == 0L) {
                    log.info { "Inventory events sent: $count, type=${event.eventType}, offset=${result.recordMetadata.offset()}" }
                } else {
                    log.debug { "Sent inventory event: ${event.eventId}, type=${event.eventType}" }
                }
            } else {
                failedCounter.increment()
                log.error(ex) { "Failed to send inventory event: ${event.eventId}" }
            }
        }
    }

    fun stopSimulation() {
        if (isRunning.compareAndSet(true, false)) {
            log.info { "Stopping inventory simulation..." }
            simulationJob?.cancel()
            simulationJob = null

            try {
                inventoryKafkaTemplate.flush()
            } catch (e: Exception) {
                log.error(e) { "Failed to flush inventory Kafka producer" }
            }

            log.info { "Inventory simulation stopped. Total events sent: ${totalEventsSent.get()}" }
        }
    }

    fun getStatus(): InventorySimulationStatus {
        return InventorySimulationStatus(
            isRunning = isRunning.get(),
            totalEventsSent = totalEventsSent.get(),
            intervalMs = properties.inventoryIntervalMs,
            catalogSize = catalog.size()
        )
    }

    @PreDestroy
    fun cleanup() {
        stopSimulation()
        scope.cancel()
        virtualThreadDispatcher.close()
    }

    data class InventorySimulationStatus(
        val isRunning: Boolean,
        val totalEventsSent: Long,
        val intervalMs: Long,
        val catalogSize: Int
    )
}
