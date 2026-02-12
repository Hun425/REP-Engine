package com.rep.simulator.tracing

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "tracing")
data class TracingProperties(
    val jaeger: JaegerProperties = JaegerProperties(),
    val anomaly: AnomalyProperties = AnomalyProperties()
)

data class JaegerProperties(
    val queryUrl: String = "http://localhost:16686"
)

data class AnomalyProperties(
    val slowThresholdMs: Long = 500,
    val errorScanEnabled: Boolean = true,
    val scanIntervalMinutes: Long = 5,
    val retentionDays: Int = 30
)
