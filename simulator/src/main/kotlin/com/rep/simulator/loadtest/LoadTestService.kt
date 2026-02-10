package com.rep.simulator.loadtest

import com.rep.simulator.service.InventorySimulator
import com.rep.simulator.service.TrafficSimulator
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = KotlinLogging.logger {}

/**
 * 부하 테스트 오케스트레이션 서비스.
 *
 * 한 번에 하나의 테스트만 실행 가능.
 * 시나리오별로 TrafficSimulator, InventorySimulator, RecommendationLoadGenerator를 조합.
 */
@Service
class LoadTestService(
    private val trafficSimulator: TrafficSimulator,
    private val inventorySimulator: InventorySimulator,
    private val recLoadGenerator: RecommendationLoadGenerator,
    private val metricsCollector: MetricsCollector,
    private val resultStore: LoadTestResultStore,
    private val properties: LoadTestProperties
) {
    private val virtualThreadDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(virtualThreadDispatcher + SupervisorJob())
    private val lock = ReentrantLock()

    @Volatile private var currentTestId: String? = null
    @Volatile private var currentScenario: LoadTestScenario? = null
    @Volatile private var currentConfig: LoadTestConfig? = null
    @Volatile private var currentPhase: LoadTestPhase = LoadTestPhase.NOT_STARTED
    @Volatile private var startedAt: Instant? = null
    @Volatile private var currentStage: Int = 0
    @Volatile private var totalStages: Int = 0
    @Volatile private var latestMetrics: LoadTestMetrics? = null

    private val metricsTimeSeries = mutableListOf<TimestampedMetrics>()
    private var orchestratorJob: Job? = null
    private var metricsJob: Job? = null

    fun startTest(request: LoadTestStartRequest): LoadTestStatus {
        lock.withLock {
            if (currentPhase == LoadTestPhase.RUNNING || currentPhase == LoadTestPhase.STOPPING) {
                throw IllegalStateException("A load test is already running (phase=$currentPhase)")
            }

            val testId = "lt-${System.currentTimeMillis()}"
            currentTestId = testId
            currentScenario = request.scenario
            currentConfig = request.config
            currentPhase = LoadTestPhase.RUNNING
            startedAt = Instant.now()
            currentStage = 0
            totalStages = 0
            latestMetrics = null
            metricsTimeSeries.clear()

            log.info { "Starting load test $testId: scenario=${request.scenario}" }

            // Start metrics collection loop
            metricsJob = scope.launch {
                while (isActive) {
                    try {
                        val prometheusMetrics = metricsCollector.collect()
                        val recStats = recLoadGenerator.getStats()
                        val combined = prometheusMetrics.copy(
                            totalRequestsSent = recStats.totalRequests,
                            totalErrors = recStats.totalErrors,
                            avgLatencyMs = recStats.avgLatencyMs
                        )
                        latestMetrics = combined
                        val elapsed = java.time.Duration.between(startedAt, Instant.now()).seconds
                        metricsTimeSeries.add(TimestampedMetrics(Instant.now(), elapsed, combined))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn { "Metrics collection failed: ${e.message}" }
                    }
                    delay(properties.metricsCollectIntervalMs)
                }
            }

            // Start scenario orchestrator
            orchestratorJob = scope.launch {
                try {
                    when (request.scenario) {
                        LoadTestScenario.PIPELINE_STRESS -> runPipelineStress(request.config)
                        LoadTestScenario.RECOMMENDATION_LOAD -> runRecommendationLoad(request.config)
                        LoadTestScenario.NOTIFICATION_LOAD -> runNotificationLoad(request.config)
                    }
                    completeTest()
                } catch (e: CancellationException) {
                    log.info { "Load test $testId was cancelled" }
                } catch (e: Exception) {
                    log.error(e) { "Load test $testId failed" }
                    currentPhase = LoadTestPhase.FAILED
                    saveResult()
                }
            }

            return getStatus()
        }
    }

    fun stopTest(): LoadTestStatus {
        lock.withLock {
            if (currentPhase != LoadTestPhase.RUNNING) {
                return getStatus()
            }

            log.info { "Stopping load test $currentTestId" }
            currentPhase = LoadTestPhase.STOPPING

            // Stop all generators
            trafficSimulator.stopSimulation()
            inventorySimulator.stopSimulation()
            recLoadGenerator.stop()

            // Cancel orchestrator
            orchestratorJob?.cancel()
            orchestratorJob = null

            // Cancel metrics collection
            metricsJob?.cancel()
            metricsJob = null

            saveResult()
            currentPhase = LoadTestPhase.COMPLETED

            return getStatus()
        }
    }

    fun getStatus(): LoadTestStatus {
        val elapsed = if (startedAt != null && currentPhase == LoadTestPhase.RUNNING) {
            java.time.Duration.between(startedAt, Instant.now()).seconds
        } else {
            0L
        }

        return LoadTestStatus(
            id = currentTestId,
            scenario = currentScenario,
            phase = currentPhase,
            startedAt = startedAt,
            elapsedSec = elapsed,
            currentStage = currentStage,
            totalStages = totalStages,
            metrics = latestMetrics
        )
    }

    // === Scenario Orchestrators ===

    private suspend fun runPipelineStress(config: LoadTestConfig) {
        val stages = config.stages ?: listOf(
            StageConfig(100, 30, 5),
            StageConfig(300, 30, 5),
            StageConfig(500, 30, 5),
            StageConfig(800, 30, 10),
            StageConfig(1000, 60, 15)
        )
        totalStages = stages.size

        for ((index, stage) in stages.withIndex()) {
            currentStage = index + 1
            log.info { "Pipeline stress stage ${currentStage}/${totalStages}: ${stage.userCount} users for ${stage.durationSec}s" }

            trafficSimulator.startSimulation(stage.userCount, config.delayMillis)

            delay(stage.durationSec * 1000L)

            trafficSimulator.stopSimulation()

            if (stage.cooldownSec > 0) {
                log.info { "Cooldown: ${stage.cooldownSec}s" }
                delay(stage.cooldownSec * 1000L)
            }
        }
    }

    private suspend fun runRecommendationLoad(config: LoadTestConfig) {
        totalStages = 1
        currentStage = 1
        log.info { "Recommendation load: ${config.concurrentUsers} users, interval=${config.requestIntervalMs}ms, duration=${config.durationSec}s" }

        recLoadGenerator.start(config.concurrentUsers, config.requestIntervalMs)

        delay(config.durationSec * 1000L)

        recLoadGenerator.stop()
    }

    private suspend fun runNotificationLoad(config: LoadTestConfig) {
        totalStages = 4

        // Phase A: Traffic warmup (30s)
        currentStage = 1
        log.info { "Notification load Phase A: traffic warmup 500 users, 30s" }
        trafficSimulator.startSimulation(500, config.delayMillis)
        delay(30_000)

        // Phase B: Add inventory + recommendation load (120s)
        currentStage = 2
        log.info { "Notification load Phase B: +inventory +recLoad, 120s" }
        if (config.inventoryEnabled) {
            inventorySimulator.startSimulation()
        }
        recLoadGenerator.start(config.concurrentUsers, config.requestIntervalMs)
        delay(120_000)

        // Phase C: Stop traffic + rec, keep inventory (90s)
        currentStage = 3
        log.info { "Notification load Phase C: inventory only, 90s" }
        trafficSimulator.stopSimulation()
        recLoadGenerator.stop()
        delay(90_000)

        // Phase D: Cooldown (60s)
        currentStage = 4
        log.info { "Notification load Phase D: cooldown 60s" }
        inventorySimulator.stopSimulation()
        delay(60_000)
    }

    // === Helpers ===

    private fun completeTest() {
        metricsJob?.cancel()
        metricsJob = null
        saveResult()
        currentPhase = LoadTestPhase.COMPLETED
        log.info { "Load test $currentTestId completed" }
    }

    private fun saveResult() {
        val testId = currentTestId ?: return
        val scenario = currentScenario ?: return
        val config = currentConfig ?: return
        val start = startedAt ?: return
        val now = Instant.now()

        val result = LoadTestResult(
            id = testId,
            scenario = scenario,
            config = config,
            startedAt = start,
            completedAt = now,
            durationSec = java.time.Duration.between(start, now).seconds,
            finalMetrics = latestMetrics ?: LoadTestMetrics(),
            metricsTimeSeries = metricsTimeSeries.toList()
        )

        try {
            resultStore.save(result)
        } catch (e: Exception) {
            log.error(e) { "Failed to save load test result $testId" }
        }
    }
}
