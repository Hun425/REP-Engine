package com.rep.simulator.tracing

import com.rep.simulator.tracing.model.*
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Instant

private val log = KotlinLogging.logger {}

@Service
class TracingService(
    properties: TracingProperties,
    webClientBuilder: WebClient.Builder
) {
    private val jaegerClient: WebClient = webClientBuilder
        .baseUrl(properties.jaeger.queryUrl)
        .build()

    fun getServices(): List<String> {
        return try {
            val response = jaegerClient.get()
                .uri("/api/services")
                .retrieve()
                .bodyToMono<JaegerServicesResponse>()
                .block()

            response?.data ?: emptyList()
        } catch (e: Exception) {
            log.error(e) { "Failed to get services from Jaeger" }
            emptyList()
        }
    }

    fun searchTraces(
        service: String?,
        limit: Int = 20,
        minDuration: String? = null,
        maxDuration: String? = null,
        start: Long? = null,
        end: Long? = null
    ): List<JaegerTrace> {
        return try {
            val response = jaegerClient.get()
                .uri { uriBuilder ->
                    uriBuilder.path("/api/traces")
                    if (service != null) uriBuilder.queryParam("service", service)
                    uriBuilder.queryParam("limit", limit)
                    if (minDuration != null) uriBuilder.queryParam("minDuration", minDuration)
                    if (maxDuration != null) uriBuilder.queryParam("maxDuration", maxDuration)
                    if (start != null) uriBuilder.queryParam("start", start)
                    if (end != null) uriBuilder.queryParam("end", end)
                    uriBuilder.build()
                }
                .retrieve()
                .bodyToMono<JaegerTracesResponse>()
                .block()

            response?.data ?: emptyList()
        } catch (e: Exception) {
            log.error(e) { "Failed to search traces from Jaeger" }
            emptyList()
        }
    }

    fun getTrace(traceId: String): JaegerTrace? {
        return try {
            val response = jaegerClient.get()
                .uri("/api/traces/{traceId}", traceId)
                .retrieve()
                .bodyToMono<JaegerTracesResponse>()
                .block()

            response?.data?.firstOrNull()
        } catch (e: Exception) {
            log.error(e) { "Failed to get trace $traceId from Jaeger" }
            null
        }
    }

    fun toTraceSummaries(traces: List<JaegerTrace>): List<TraceSummary> {
        return traces.map { trace ->
            val rootSpan = trace.spans.minByOrNull { it.startTime }
            val traceEndTime = trace.spans.maxOfOrNull { it.startTime + it.duration } ?: 0
            val traceDurationUs = if (rootSpan != null) traceEndTime - rootSpan.startTime else 0
            val services = trace.spans.map { trace.processes[it.processID]?.serviceName }.distinct()
            val hasError = trace.spans.any { span ->
                span.tags.any { it.key == "error" && it.value == true } ||
                    span.tags.any { it.key == "otel.status_code" && it.value == "ERROR" }
            }

            TraceSummary(
                traceId = trace.traceID,
                rootServiceName = rootSpan?.let { trace.processes[it.processID]?.serviceName } ?: "unknown",
                rootOperationName = rootSpan?.operationName ?: "unknown",
                durationMs = traceDurationUs / 1000,
                spanCount = trace.spans.size,
                serviceCount = services.size,
                hasError = hasError,
                startTime = rootSpan?.startTime ?: 0
            )
        }
    }
}
