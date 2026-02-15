package com.rep.simulator.tracing

import com.rep.simulator.tracing.model.*
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

@Component
class AnomalyDetector(
    private val tracingService: TracingService,
    private val anomalyRepository: AnomalyRepository,
    private val properties: TracingProperties
) {

    private val scanning = AtomicBoolean(false)

    @Scheduled(fixedDelayString = "#{${tracing.anomaly.scan-interval-minutes:5} * 60000}")
    fun scheduledScan() {
        if (!properties.anomaly.errorScanEnabled) return
        try {
            scan()
        } catch (e: Exception) {
            log.error(e) { "Scheduled anomaly scan failed" }
        }
    }

    fun scan(): AnomalyScanResult {
        if (!scanning.compareAndSet(false, true)) {
            log.info { "Anomaly scan already in progress, skipping" }
            return AnomalyScanResult(newAnomalies = 0, totalScanned = 0, scanDurationMs = 0)
        }
        val startTime = System.currentTimeMillis()
        var totalScanned = 0
        var newAnomalies = 0

        try {
            val services = tracingService.getServices()

            for (service in services) {
                val traces = tracingService.searchTraces(
                    service = service,
                    limit = 50,
                    start = Instant.now().minusSeconds(properties.anomaly.scanIntervalMinutes * 60).toEpochMilli() * 1000,
                    end = Instant.now().toEpochMilli() * 1000
                )

                for (trace in traces) {
                    totalScanned++
                    newAnomalies += analyzeTrace(trace)
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Anomaly scan failed" }
        } finally {
            scanning.set(false)
        }

        val duration = System.currentTimeMillis() - startTime
        log.info { "Anomaly scan completed: scanned=$totalScanned, newAnomalies=$newAnomalies, duration=${duration}ms" }

        return AnomalyScanResult(
            newAnomalies = newAnomalies,
            totalScanned = totalScanned,
            scanDurationMs = duration
        )
    }

    private fun analyzeTrace(trace: JaegerTrace): Int {
        var count = 0

        // Total trace duration (microseconds -> milliseconds)
        val rootSpan = trace.spans.minByOrNull { it.startTime }
        val traceEndTime = trace.spans.maxOfOrNull { it.startTime + it.duration } ?: 0
        val traceDurationMs = if (rootSpan != null) (traceEndTime - rootSpan.startTime) / 1000 else 0

        // SLOW_TRACE detection
        if (traceDurationMs > properties.anomaly.slowThresholdMs) {
            if (!anomalyRepository.existsByTraceIdAndType(trace.traceID, AnomalyType.SLOW_TRACE)) {
                val serviceName = rootSpan?.let { trace.processes[it.processID]?.serviceName } ?: "unknown"
                anomalyRepository.save(
                    TraceAnomaly(
                        traceId = trace.traceID,
                        type = AnomalyType.SLOW_TRACE,
                        severity = Severity.WARNING,
                        serviceName = serviceName,
                        operationName = rootSpan?.operationName ?: "unknown",
                        durationMs = traceDurationMs,
                        thresholdMs = properties.anomaly.slowThresholdMs,
                        spanCount = trace.spans.size
                    )
                )
                count++
            }
        }

        // Per-span analysis
        for (span in trace.spans) {
            val spanDurationMs = span.duration / 1000
            val serviceName = trace.processes[span.processID]?.serviceName ?: "unknown"

            // ERROR_SPAN detection
            val hasError = span.tags.any { it.key == "error" && it.value == true } ||
                span.tags.any { it.key == "otel.status_code" && it.value == "ERROR" }

            if (hasError) {
                if (!anomalyRepository.existsByTraceIdAndType(trace.traceID, AnomalyType.ERROR_SPAN)) {
                    val errorMsg = span.tags.find { it.key == "error.message" || it.key == "otel.status_description" }?.value?.toString()
                    anomalyRepository.save(
                        TraceAnomaly(
                            traceId = trace.traceID,
                            type = AnomalyType.ERROR_SPAN,
                            severity = Severity.ERROR,
                            serviceName = serviceName,
                            operationName = span.operationName,
                            durationMs = spanDurationMs,
                            errorMessage = errorMsg
                        )
                    )
                    count++
                }
            }

            // DLQ_ROUTED detection
            if (span.operationName.contains("dlq", ignoreCase = true) ||
                span.tags.any { it.key == "messaging.destination.name" && it.value.toString().contains(".dlq") }) {
                if (!anomalyRepository.existsByTraceIdAndType(trace.traceID, AnomalyType.DLQ_ROUTED)) {
                    anomalyRepository.save(
                        TraceAnomaly(
                            traceId = trace.traceID,
                            type = AnomalyType.DLQ_ROUTED,
                            severity = Severity.CRITICAL,
                            serviceName = serviceName,
                            operationName = span.operationName,
                            durationMs = spanDurationMs
                        )
                    )
                    count++
                }
            }
        }

        return count
    }
}
