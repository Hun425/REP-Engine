package com.rep.simulator.tracing

import com.rep.simulator.tracing.model.AnomalyScanResult
import com.rep.simulator.tracing.model.AnomalyType
import com.rep.simulator.tracing.model.CreateBookmarkRequest
import com.rep.simulator.tracing.model.JaegerTrace
import com.rep.simulator.tracing.model.TraceAnomaly
import com.rep.simulator.tracing.model.TraceBookmark
import com.rep.simulator.tracing.model.TraceSummary
import com.rep.simulator.tracing.model.UpdateBookmarkNoteRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tracing")
class TracingController(
    private val tracingService: TracingService,
    private val anomalyDetector: AnomalyDetector,
    private val anomalyRepository: AnomalyRepository,
) {
    // ============================================
    // Trace Query (Jaeger Proxy)
    // ============================================

    @GetMapping("/services")
    fun getServices(): ResponseEntity<List<String>> = ResponseEntity.ok(tracingService.getServices())

    @GetMapping("/traces")
    fun searchTraces(
        @RequestParam(required = false) service: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) minDuration: String?,
        @RequestParam(required = false) maxDuration: String?,
        @RequestParam(required = false) start: Long?,
        @RequestParam(required = false) end: Long?,
    ): ResponseEntity<List<TraceSummary>> {
        val traces = tracingService.searchTraces(service, limit, minDuration, maxDuration, start, end)
        return ResponseEntity.ok(tracingService.toTraceSummaries(traces))
    }

    @GetMapping("/traces/{traceId}")
    fun getTraceDetail(
        @PathVariable traceId: String,
    ): ResponseEntity<JaegerTrace> {
        val trace =
            tracingService.getTrace(traceId)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(trace)
    }

    // ============================================
    // Anomalies
    // ============================================

    @GetMapping("/anomalies")
    fun getAnomalies(
        @RequestParam(required = false) type: AnomalyType?,
        @RequestParam(required = false) service: String?,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<List<TraceAnomaly>> {
        val anomalies = anomalyRepository.searchAnomalies(type, service, from, to, page, size)
        return ResponseEntity.ok(anomalies)
    }

    @PostMapping("/anomalies/detect")
    fun triggerAnomalyScan(): ResponseEntity<AnomalyScanResult> {
        val result = anomalyDetector.scan()
        return ResponseEntity.ok(result)
    }

    // ============================================
    // Bookmarks
    // ============================================

    @GetMapping("/bookmarks")
    fun getBookmarks(): ResponseEntity<List<TraceBookmark>> = ResponseEntity.ok(anomalyRepository.getBookmarks())

    @PostMapping("/bookmarks")
    fun addBookmark(
        @RequestBody request: CreateBookmarkRequest,
    ): ResponseEntity<Map<String, String>> {
        val bookmark =
            TraceBookmark(
                traceId = request.traceId,
                serviceName = request.serviceName,
                operationName = request.operationName,
                durationMs = request.durationMs,
                note = request.note,
            )
        val id = anomalyRepository.saveBookmark(bookmark)
        return ResponseEntity.ok(mapOf("id" to id))
    }

    @DeleteMapping("/bookmarks/{id}")
    fun deleteBookmark(
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        anomalyRepository.deleteBookmark(id)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/bookmarks/{id}")
    fun updateBookmarkNote(
        @PathVariable id: String,
        @RequestBody request: UpdateBookmarkNoteRequest,
    ): ResponseEntity<Void> {
        anomalyRepository.updateBookmarkNote(id, request.note)
        return ResponseEntity.noContent().build()
    }
}
