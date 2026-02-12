package com.rep.simulator.tracing.model

import java.time.Instant

// ============================================
// Jaeger API Response Models
// ============================================

data class JaegerServicesResponse(
    val data: List<String> = emptyList()
)

data class JaegerTracesResponse(
    val data: List<JaegerTrace> = emptyList()
)

data class JaegerTrace(
    val traceID: String,
    val spans: List<JaegerSpan> = emptyList(),
    val processes: Map<String, JaegerProcess> = emptyMap()
)

data class JaegerSpan(
    val traceID: String,
    val spanID: String,
    val operationName: String,
    val references: List<SpanReference> = emptyList(),
    val startTime: Long,
    val duration: Long,
    val tags: List<JaegerTag> = emptyList(),
    val logs: List<JaegerLog> = emptyList(),
    val processID: String
)

data class SpanReference(
    val refType: String,
    val traceID: String,
    val spanID: String
)

data class JaegerTag(
    val key: String,
    val type: String,
    val value: Any?
)

data class JaegerLog(
    val timestamp: Long,
    val fields: List<JaegerTag> = emptyList()
)

data class JaegerProcess(
    val serviceName: String,
    val tags: List<JaegerTag> = emptyList()
)

// ============================================
// Trace Summary (for list view)
// ============================================

data class TraceSummary(
    val traceId: String,
    val rootServiceName: String,
    val rootOperationName: String,
    val durationMs: Long,
    val spanCount: Int,
    val serviceCount: Int,
    val hasError: Boolean,
    val startTime: Long
)

// ============================================
// Anomaly Models
// ============================================

enum class AnomalyType {
    SLOW_TRACE, SLOW_SPAN, ERROR_SPAN, DLQ_ROUTED, HIGH_RETRY
}

enum class Severity {
    CRITICAL, ERROR, WARNING
}

data class TraceAnomaly(
    val id: String? = null,
    val traceId: String,
    val type: AnomalyType,
    val severity: Severity,
    val serviceName: String,
    val operationName: String,
    val durationMs: Long,
    val thresholdMs: Long? = null,
    val errorMessage: String? = null,
    val spanCount: Int? = null,
    val metadata: Map<String, Any?>? = null,
    val note: String? = null,
    val isBookmark: Boolean = false,
    val detectedAt: Instant = Instant.now(),
    val createdAt: Instant = Instant.now()
)

// ============================================
// Bookmark Models
// ============================================

data class TraceBookmark(
    val id: String? = null,
    val traceId: String,
    val serviceName: String,
    val operationName: String,
    val durationMs: Long,
    val note: String? = null,
    val createdAt: Instant = Instant.now()
)

data class CreateBookmarkRequest(
    val traceId: String,
    val serviceName: String,
    val operationName: String,
    val durationMs: Long,
    val note: String? = null
)

data class UpdateBookmarkNoteRequest(
    val note: String
)

// ============================================
// API Response Wrappers
// ============================================

data class AnomalySearchParams(
    val type: AnomalyType? = null,
    val service: String? = null,
    val from: Long? = null,
    val to: Long? = null,
    val page: Int = 0,
    val size: Int = 20
)

data class AnomalyScanResult(
    val newAnomalies: Int,
    val totalScanned: Int,
    val scanDurationMs: Long
)
