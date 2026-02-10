package com.rep.simulator.loadtest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val log = KotlinLogging.logger {}

/**
 * JSON 파일 기반 부하 테스트 결과 저장소.
 */
@Component
class LoadTestResultStore(
    private val properties: LoadTestProperties
) {
    private val objectMapper = ObjectMapper().apply {
        registerModule(JavaTimeModule())
        registerModule(KotlinModule.Builder().build())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    private val resultsPath: Path
        get() = Paths.get(properties.resultsDir)

    @PostConstruct
    fun init() {
        Files.createDirectories(resultsPath)
        log.info { "Load test results directory: ${resultsPath.toAbsolutePath()}" }
    }

    fun save(result: LoadTestResult) {
        val file = resultsPath.resolve("${result.id}.json")
        objectMapper.writeValue(file.toFile(), result)
        log.info { "Saved load test result: ${result.id}" }
    }

    fun findAll(): List<LoadTestResultSummary> {
        if (!Files.exists(resultsPath)) return emptyList()

        return Files.list(resultsPath)
            .filter { it.toString().endsWith(".json") }
            .map { path ->
                try {
                    val result = objectMapper.readValue<LoadTestResult>(path.toFile())
                    result.toSummary()
                } catch (e: Exception) {
                    log.warn { "Failed to read result file ${path.fileName}: ${e.message}" }
                    null
                }
            }
            .filter { it != null }
            .map { it!! }
            .sorted(Comparator.comparing(LoadTestResultSummary::startedAt).reversed())
            .toList()
    }

    fun findById(id: String): LoadTestResult? {
        val file = resultsPath.resolve("$id.json")
        if (!Files.exists(file)) return null
        return try {
            objectMapper.readValue<LoadTestResult>(file.toFile())
        } catch (e: Exception) {
            log.warn { "Failed to read result $id: ${e.message}" }
            null
        }
    }

    fun delete(id: String): Boolean {
        val file = resultsPath.resolve("$id.json")
        return try {
            Files.deleteIfExists(file)
        } catch (e: Exception) {
            log.warn { "Failed to delete result $id: ${e.message}" }
            false
        }
    }

    fun updateNote(id: String, note: String): Boolean {
        val result = findById(id) ?: return false
        val updated = result.copy(note = note)
        save(updated)
        return true
    }

    private fun LoadTestResult.toSummary() = LoadTestResultSummary(
        id = id,
        scenario = scenario,
        startedAt = startedAt,
        durationSec = durationSec,
        note = note,
        recApiP95Ms = finalMetrics.recApiP95Ms,
        recApiP99Ms = finalMetrics.recApiP99Ms,
        kafkaConsumerLag = finalMetrics.kafkaConsumerLag,
        totalErrors = finalMetrics.totalErrors,
        totalRequestsSent = finalMetrics.totalRequestsSent,
        avgLatencyMs = finalMetrics.avgLatencyMs
    )
}
