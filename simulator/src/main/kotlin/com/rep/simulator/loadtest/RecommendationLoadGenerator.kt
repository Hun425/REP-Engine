package com.rep.simulator.loadtest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

/**
 * 추천 API에 동시 HTTP 요청을 생성하는 부하 생성기.
 *
 * TrafficSimulator와 동일한 Virtual Thread + Coroutine 패턴 사용.
 */
@Component
class RecommendationLoadGenerator(
    private val properties: LoadTestProperties,
) {
    private val virtualThreadDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(virtualThreadDispatcher + SupervisorJob())
    private var loadJob: Job? = null

    private val isRunning = AtomicBoolean(false)
    private val totalRequests = AtomicLong(0)
    private val totalErrors = AtomicLong(0)
    private val totalLatencyMs = AtomicLong(0)

    private val restTemplate =
        RestTemplateBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(5))
            .build()

    fun start(
        concurrentUsers: Int,
        requestIntervalMs: Long,
    ) {
        if (isRunning.compareAndSet(false, true)) {
            totalRequests.set(0)
            totalErrors.set(0)
            totalLatencyMs.set(0)

            log.info { "Starting recommendation load: $concurrentUsers users, interval=${requestIntervalMs}ms" }

            loadJob =
                scope.launch {
                    (1..concurrentUsers)
                        .map { i ->
                            async {
                                val userId = "USER-${i.toString().padStart(6, '0')}"
                                runUserLoop(userId, requestIntervalMs)
                            }
                        }.awaitAll()
                }
        } else {
            log.warn { "Recommendation load generator is already running" }
        }
    }

    private suspend fun runUserLoop(
        userId: String,
        intervalMs: Long,
    ) {
        while (currentCoroutineContext().isActive) {
            try {
                val startTime = System.currentTimeMillis()
                val url = "${properties.recommendationApiUrl}/api/v1/recommendations/$userId"
                restTemplate.getForObject(url, String::class.java)
                val latency = System.currentTimeMillis() - startTime

                totalRequests.incrementAndGet()
                totalLatencyMs.addAndGet(latency)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                totalRequests.incrementAndGet()
                totalErrors.incrementAndGet()
                log.debug { "Request failed for $userId: ${e.message}" }
            }

            delay(intervalMs)
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            log.info { "Stopping recommendation load generator" }
            loadJob?.cancel()
            loadJob = null
        }
    }

    fun getStats(): LoadGeneratorStats {
        val requests = totalRequests.get()
        val avgLatency =
            if (requests - totalErrors.get() > 0) {
                totalLatencyMs.get().toDouble() / (requests - totalErrors.get())
            } else {
                0.0
            }
        return LoadGeneratorStats(
            totalRequests = requests,
            totalErrors = totalErrors.get(),
            avgLatencyMs = avgLatency,
        )
    }

    fun isActive(): Boolean = isRunning.get()

    data class LoadGeneratorStats(
        val totalRequests: Long,
        val totalErrors: Long,
        val avgLatencyMs: Double,
    )
}
