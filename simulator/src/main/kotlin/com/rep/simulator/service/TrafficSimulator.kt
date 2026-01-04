package com.rep.simulator.service

import com.rep.event.user.UserActionEvent
import com.rep.simulator.config.SimulatorProperties
import com.rep.simulator.domain.UserSession
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private val log = KotlinLogging.logger {}

/**
 * 트래픽 시뮬레이터
 *
 * 다수의 가상 유저가 동시에 활동하는 상황을 시뮬레이션합니다.
 * Java 25 Virtual Threads + Kotlin Coroutines 조합으로 수만 명의 유저를 경량 처리합니다.
 *
 * @see <a href="docs/adr-001-concurrency-strategy.md">ADR-001: 동시성 처리 전략</a>
 */
@Service
class TrafficSimulator(
    private val kafkaTemplate: KafkaTemplate<String, UserActionEvent>,
    private val properties: SimulatorProperties,
    private val meterRegistry: MeterRegistry
) {
    // Java 25 Virtual Threads 기반 Dispatcher
    // Blocking I/O 호출 시에도 시스템 처리량 유지
    private val virtualThreadDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(virtualThreadDispatcher + SupervisorJob())
    private var simulationJob: Job? = null

    // Metrics
    private val sentCounter: Counter = Counter.builder("simulator.events.sent")
        .tag("topic", properties.topic)
        .register(meterRegistry)

    private val failedCounter: Counter = Counter.builder("simulator.events.failed")
        .tag("topic", properties.topic)
        .register(meterRegistry)

    private val totalEventsSent = AtomicLong(0)
    private val activeSessionCount = AtomicInteger(0)

    init {
        // 활성 세션 수 Gauge 등록
        Gauge.builder("simulator.sessions.active") { activeSessionCount.get() }
            .description("Number of active user sessions")
            .register(meterRegistry)
    }

    /**
     * 시뮬레이션을 시작합니다.
     *
     * @param userCount 시뮬레이션할 가상 유저 수
     * @param delayMillis 유저당 행동 간 지연 시간 (밀리초)
     */
    fun startSimulation(
        userCount: Int = properties.userCount,
        delayMillis: Long = properties.delayMillis
    ) {
        if (simulationJob?.isActive == true) {
            log.warn { "Simulation is already running" }
            return
        }

        log.info { "Starting traffic simulation with $userCount users, delay=${delayMillis}ms" }

        simulationJob = scope.launch {
            val sessions = (1..userCount).map { i ->
                async {
                    activeSessionCount.incrementAndGet()
                    try {
                        val session = UserSession(
                            userId = "USER-${i.toString().padStart(6, '0')}",
                            productCountPerCategory = properties.productCountPerCategory
                        )
                        runUserSession(session, delayMillis)
                    } finally {
                        activeSessionCount.decrementAndGet()
                    }
                }
            }

            // 모든 세션이 취소될 때까지 대기
            sessions.awaitAll()
        }
    }

    /**
     * 개별 유저 세션을 실행합니다.
     */
    private suspend fun runUserSession(session: UserSession, delayMillis: Long) {
        log.debug { "Starting session for ${session.userId}" }

        while (currentCoroutineContext().isActive) {
            try {
                val event = session.nextAction()
                sendToKafka(event)

                // 랜덤 지연 (delayMillis ~ delayMillis * 2)
                delay(Random.nextLong(delayMillis, delayMillis * 2))
            } catch (e: CancellationException) {
                log.debug { "Session ${session.userId} cancelled" }
                throw e
            } catch (e: Exception) {
                log.error(e) { "Error in session ${session.userId}" }
                delay(1000) // 오류 시 1초 대기 후 재시도
            }
        }
    }

    /**
     * Kafka로 이벤트를 전송합니다.
     */
    private fun sendToKafka(event: UserActionEvent) {
        val future = kafkaTemplate.send(properties.topic, event.userId.toString(), event)

        future.whenComplete { result, ex ->
            if (ex == null) {
                sentCounter.increment()
                val count = totalEventsSent.incrementAndGet()

                // 1000건마다 로그 출력
                if (count % 1000 == 0L) {
                    log.info { "Total events sent: $count, offset: ${result.recordMetadata.offset()}" }
                } else {
                    log.debug { "Sent event: ${event.traceId}, offset: ${result.recordMetadata.offset()}" }
                }
            } else {
                failedCounter.increment()
                log.error(ex) { "Failed to send event: ${event.traceId}" }
            }
        }
    }

    /**
     * 시뮬레이션을 중지합니다.
     */
    fun stopSimulation() {
        log.info { "Stopping traffic simulation..." }
        simulationJob?.cancel()
        simulationJob = null
        log.info { "Traffic simulation stopped. Total events sent: ${totalEventsSent.get()}" }
    }

    /**
     * 시뮬레이션 상태를 반환합니다.
     */
    fun getStatus(): SimulationStatus {
        return SimulationStatus(
            isRunning = simulationJob?.isActive == true,
            totalEventsSent = totalEventsSent.get(),
            userCount = properties.userCount,
            delayMillis = properties.delayMillis
        )
    }

    @PreDestroy
    fun cleanup() {
        log.info { "Cleaning up TrafficSimulator..." }
        stopSimulation()
        scope.cancel()
        virtualThreadDispatcher.close()
    }

    data class SimulationStatus(
        val isRunning: Boolean,
        val totalEventsSent: Long,
        val userCount: Int,
        val delayMillis: Long
    )
}
