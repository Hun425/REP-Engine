package com.rep.consumer.service

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.BulkResponse
import com.rep.consumer.config.ConsumerProperties
import com.rep.event.user.UserActionEvent
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * Elasticsearch Bulk Indexer
 *
 * Kafka에서 수신한 이벤트를 ES에 일괄 저장합니다.
 * Virtual Threads를 활용하여 고성능 처리를 구현합니다.
 *
 * 주요 기능:
 * - Bulk API를 통한 일괄 인덱싱
 * - 재시도 로직 (기본 3회)
 * - 실패 시 DLQ 전송
 * - traceId 기반 멱등성 보장
 *
 * @see <a href="docs/phase 2.md">Phase 2: Data Pipeline</a>
 */
@Component
class BulkIndexer(
    private val esClient: ElasticsearchClient,
    private val dlqProducer: DlqProducer,
    private val properties: ConsumerProperties,
    private val meterRegistry: MeterRegistry
) {
    @Value("\${elasticsearch.index.user-behavior:user_behavior_index}")
    private lateinit var indexName: String

    // Metrics - @PostConstruct에서 초기화 (indexName이 주입된 후)
    private lateinit var bulkSuccessCounter: Counter
    private lateinit var bulkFailedCounter: Counter
    private lateinit var bulkBatchFailedCounter: Counter  // 배치 단위 실패 카운터
    private lateinit var retryCounter: Counter

    @PostConstruct
    fun initMetrics() {
        bulkSuccessCounter = Counter.builder("es.bulk.success")
            .tag("index", indexName)
            .register(meterRegistry)

        bulkFailedCounter = Counter.builder("es.bulk.failed")
            .tag("index", indexName)
            .description("Failed documents count")
            .register(meterRegistry)

        bulkBatchFailedCounter = Counter.builder("es.bulk.batch.failed")
            .tag("index", indexName)
            .description("Failed batch count (all retries exhausted)")
            .register(meterRegistry)

        retryCounter = Counter.builder("es.bulk.retry")
            .tag("index", indexName)
            .register(meterRegistry)

        log.info { "BulkIndexer initialized with index: $indexName" }
    }

    /**
     * 이벤트 배치를 ES에 저장합니다.
     * Kafka Consumer에서 오프셋 커밋 전에 반드시 저장 완료를 보장합니다.
     *
     * 재시도 로직:
     * - 최대 3회 시도 (설정 가능)
     * - 실패 시 지수 백오프 (1초, 2초, 4초)
     * - 최종 실패 시 DLQ로 전송
     *
     * @param events 저장할 이벤트 목록
     * @return 성공적으로 저장된 이벤트 수
     */
    suspend fun indexBatchSync(events: List<UserActionEvent>): Int {
        if (events.isEmpty()) return 0

        log.debug { "Indexing batch of ${events.size} events to $indexName" }

        var lastException: Exception? = null

        repeat(properties.maxRetries) { attempt ->
            try {
                val bulkRequest = buildBulkRequest(events)
                val response = esClient.bulk(bulkRequest)
                return handleBulkResponse(response, events)
            } catch (e: Exception) {
                lastException = e
                retryCounter.increment()

                if (attempt < properties.maxRetries - 1) {
                    // 지수 백오프: 1초, 2초, 4초, 8초, ...
                    val delayMs = properties.retryDelayMs * (1L shl attempt)
                    log.warn { "Bulk indexing attempt ${attempt + 1}/${properties.maxRetries} failed, retrying in ${delayMs}ms..." }
                    delay(delayMs)
                }
            }
        }

        // 모든 재시도 실패
        log.error(lastException) { "Bulk indexing failed after ${properties.maxRetries} attempts for ${events.size} events" }
        bulkBatchFailedCounter.increment()  // 배치 실패 카운트
        bulkFailedCounter.increment(events.size.toDouble())  // 문서 실패 카운트
        sendToDlq(events)
        return 0
    }

    /**
     * ES Bulk Request를 생성합니다.
     * Document ID = traceId로 설정하여 멱등성을 보장합니다 (동일 메시지 재처리 시 Upsert).
     */
    private fun buildBulkRequest(events: List<UserActionEvent>): BulkRequest {
        val bulkRequest = BulkRequest.Builder()

        events.forEach { event ->
            bulkRequest.operations { op ->
                op.index { idx ->
                    idx.index(indexName)
                        .id(event.traceId.toString())  // Idempotency 보장
                        .document(mapOf(
                            "traceId" to event.traceId.toString(),
                            "userId" to event.userId.toString(),
                            "productId" to event.productId.toString(),
                            "category" to event.category.toString(),
                            "actionType" to event.actionType.toString(),
                            "metadata" to event.metadata,
                            "timestamp" to event.timestamp.toEpochMilli()
                        ))
                }
            }
        }

        return bulkRequest.build()
    }

    /**
     * Bulk 응답을 처리하고 성공 건수를 반환합니다.
     * 개별 문서 실패 시 해당 이벤트만 DLQ로 전송합니다.
     */
    private fun handleBulkResponse(response: BulkResponse, events: List<UserActionEvent>): Int {
        var successCount = 0

        if (response.errors()) {
            response.items().forEachIndexed { index, item ->
                if (item.error() != null) {
                    log.error { "Failed to index document: ${item.error()?.reason()}" }
                    bulkFailedCounter.increment()
                    // 경계값 검증: ES 응답 items 수가 events 수와 다를 수 있음
                    if (index < events.size) {
                        dlqProducer.sendSync(events[index])
                    } else {
                        log.error { "Bulk response index $index out of bounds (events.size=${events.size})" }
                    }
                } else {
                    bulkSuccessCounter.increment()
                    successCount++
                }
            }
        } else {
            successCount = events.size
            bulkSuccessCounter.increment(events.size.toDouble())
        }

        if (successCount > 0) {
            log.info { "Bulk indexed $successCount/${events.size} documents to $indexName successfully" }
        }

        return successCount
    }

    /**
     * 전체 배치 실패 시 모든 이벤트를 DLQ로 전송합니다.
     */
    private fun sendToDlq(events: List<UserActionEvent>) {
        events.forEach { event ->
            dlqProducer.sendSync(event)
        }
    }
}
