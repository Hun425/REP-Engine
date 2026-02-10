package com.rep.simulator.loadtest

import java.time.Instant

// === Enums ===

enum class LoadTestScenario {
    PIPELINE_STRESS,
    RECOMMENDATION_LOAD,
    NOTIFICATION_LOAD
}

enum class LoadTestPhase {
    NOT_STARTED,
    RUNNING,
    STOPPING,
    COMPLETED,
    FAILED
}

// === Request Models ===

data class LoadTestStartRequest(
    val scenario: LoadTestScenario,
    val config: LoadTestConfig
)

data class LoadTestConfig(
    val stages: List<StageConfig>? = null,
    val delayMillis: Long = 500,
    val concurrentUsers: Int = 10,
    val durationSec: Int = 60,
    val requestIntervalMs: Long = 200,
    val inventoryEnabled: Boolean = true
)

data class StageConfig(
    val userCount: Int,
    val durationSec: Int,
    val cooldownSec: Int = 5
)

// === Status / Metrics ===

data class LoadTestStatus(
    val id: String?,
    val scenario: LoadTestScenario?,
    val phase: LoadTestPhase,
    val startedAt: Instant? = null,
    val elapsedSec: Long = 0,
    val currentStage: Int = 0,
    val totalStages: Int = 0,
    val metrics: LoadTestMetrics? = null
)

data class LoadTestMetrics(
    val kafkaConsumerLag: Double? = null,
    val kafkaProcessedRate: Double? = null,
    val esBulkSuccessRate: Double? = null,
    val esBulkFailedTotal: Double? = null,
    val preferenceUpdateRate: Double? = null,
    val recApiP50Ms: Double? = null,
    val recApiP95Ms: Double? = null,
    val recApiP99Ms: Double? = null,
    val notificationTriggered: Double? = null,
    val notificationRateLimited: Double? = null,
    val jvmHeapUsedBytes: Double? = null,
    val redisMemoryUsedBytes: Double? = null,
    val totalRequestsSent: Long = 0,
    val totalErrors: Long = 0,
    val avgLatencyMs: Double = 0.0
)

data class TimestampedMetrics(
    val timestamp: Instant,
    val elapsedSec: Long,
    val metrics: LoadTestMetrics
)

// === Result Models ===

data class LoadTestResult(
    val id: String,
    val scenario: LoadTestScenario,
    val config: LoadTestConfig,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationSec: Long,
    val finalMetrics: LoadTestMetrics,
    val metricsTimeSeries: List<TimestampedMetrics>,
    val note: String = ""
)

data class LoadTestResultSummary(
    val id: String,
    val scenario: LoadTestScenario,
    val startedAt: Instant,
    val durationSec: Long,
    val note: String,
    val recApiP95Ms: Double?,
    val recApiP99Ms: Double?,
    val kafkaConsumerLag: Double?,
    val totalErrors: Long,
    val totalRequestsSent: Long,
    val avgLatencyMs: Double
)

data class NoteUpdateRequest(
    val note: String
)
