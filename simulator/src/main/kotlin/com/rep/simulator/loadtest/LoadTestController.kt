package com.rep.simulator.loadtest

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 부하 테스트 REST API
 */
@RestController
@RequestMapping("/api/v1/load-test")
class LoadTestController(
    private val loadTestService: LoadTestService,
    private val resultStore: LoadTestResultStore
) {

    @PostMapping("/start")
    fun startTest(@RequestBody request: LoadTestStartRequest): ResponseEntity<LoadTestStatus> {
        return try {
            ResponseEntity.ok(loadTestService.startTest(request))
        } catch (e: IllegalStateException) {
            ResponseEntity.badRequest().build()
        }
    }

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<LoadTestStatus> {
        return ResponseEntity.ok(loadTestService.getStatus())
    }

    @PostMapping("/stop")
    fun stopTest(): ResponseEntity<LoadTestStatus> {
        return ResponseEntity.ok(loadTestService.stopTest())
    }

    @GetMapping("/results")
    fun getResults(): ResponseEntity<List<LoadTestResultSummary>> {
        return ResponseEntity.ok(resultStore.findAll())
    }

    @GetMapping("/results/{id}")
    fun getResult(@PathVariable id: String): ResponseEntity<LoadTestResult> {
        val result = resultStore.findById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/results/{id}")
    fun deleteResult(@PathVariable id: String): ResponseEntity<Void> {
        return if (resultStore.delete(id)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PatchMapping("/results/{id}/note")
    fun updateNote(
        @PathVariable id: String,
        @RequestBody request: NoteUpdateRequest
    ): ResponseEntity<Void> {
        return if (resultStore.updateNote(id, request.note)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }
}
