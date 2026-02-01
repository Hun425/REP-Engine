package com.rep.consumer.listener

import com.rep.consumer.service.BulkIndexer
import com.rep.consumer.service.PreferenceUpdater
import com.rep.event.user.UserActionEvent
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * Kafka Behavior Event Listener
 *
 * user.action.v1 토픽에서 유저 행동 이벤트를 배치로 수신하고
 * BulkIndexer를 통해 ES에 인덱싱한 후, 유저 취향 벡터를 갱신합니다.
 *
 * 중요: ES 저장이 완료된 후에만 오프셋을 커밋하여 메시지 유실을 방지합니다.
 * 취향 벡터 갱신은 best-effort로 처리됩니다 (실패해도 오프셋 커밋).
 *
 * @see <a href="docs/phase 2.md">Phase 2: Kafka Listener</a>
 * @see <a href="docs/phase 2.md">Phase 2: Preference Update</a>
 */
@Component
@OptIn(ExperimentalCoroutinesApi::class)
class BehaviorEventListener(
    private val bulkIndexer: BulkIndexer,
    private val preferenceUpdater: PreferenceUpdater,
    private val meterRegistry: MeterRegistry,
    @Qualifier("virtualThreadDispatcher") private val virtualThreadDispatcher: CloseableCoroutineDispatcher
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val processedCounter: Counter = Counter.builder("kafka.consumer.processed")
        .tag("topic", "user.action.v1")
        .register(meterRegistry)

    private val indexedCounter: Counter = Counter.builder("kafka.consumer.indexed")
        .tag("topic", "user.action.v1")
        .register(meterRegistry)

    private val errorCounter: Counter = Counter.builder("kafka.consumer.errors")
        .tag("topic", "user.action.v1")
        .register(meterRegistry)

    private val consumeTimer: Timer = Timer.builder("kafka.consumer.batch.duration")
        .tag("topic", "user.action.v1")
        .description("Time spent processing a batch of records")
        .register(meterRegistry)

    private val batchSizeSummary: DistributionSummary = DistributionSummary.builder("kafka.consumer.batch.size")
        .tag("topic", "user.action.v1")
        .description("Distribution of batch sizes")
        .publishPercentiles(0.5, 0.9, 0.99)
        .register(meterRegistry)

    /**
     * 배치 단위로 이벤트를 수신하고 처리합니다.
     *
     * 핵심 처리 흐름:
     * 1. Kafka에서 배치 수신 (MAX_POLL_RECORDS = 500)
     * 2. ES Bulk API로 동기적 저장 (저장 완료까지 대기)
     * 3. 저장 성공 시에만 오프셋 커밋
     *
     * 이를 통해 Exactly-once semantics에 가까운 처리를 보장합니다.
     */
    @KafkaListener(
        topics = ["\${consumer.topic}"],
        groupId = "\${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun consume(
        records: List<ConsumerRecord<String, UserActionEvent>>,
        acknowledgment: Acknowledgment
    ) {
        if (records.isEmpty()) {
            acknowledgment.acknowledge()
            return
        }

        val startTime = System.nanoTime()
        batchSizeSummary.record(records.size.toDouble())

        log.debug { "Received batch of ${records.size} records" }

        // runBlocking으로 코루틴 완료까지 대기 (Virtual Thread에서 실행되므로 블로킹 안전)
        runBlocking(virtualThreadDispatcher) {
            try {
                // 1. 이벤트 추출
                val events = records.map { it.value() }

                // 2. ES에 동기적으로 저장 (저장 완료까지 대기)
                val indexedCount = bulkIndexer.indexBatchSync(events)

                // 3. 유저 취향 벡터 갱신 (Phase 3)
                // Best-effort: 실패해도 ES 저장은 완료되었으므로 오프셋 커밋 진행
                try {
                    preferenceUpdater.updatePreferencesBatch(events)
                } catch (e: Exception) {
                    log.warn(e) { "Failed to update preferences, but ES indexing succeeded" }
                }

                // 4. 메트릭 업데이트
                processedCounter.increment(records.size.toDouble())
                indexedCounter.increment(indexedCount.toDouble())

                // 5. ES 저장 성공 후에만 오프셋 커밋
                acknowledgment.acknowledge()

                if (records.size >= 100) {
                    log.info { "Processed batch: received=${records.size}, indexed=$indexedCount" }
                }

            } catch (e: Exception) {
                errorCounter.increment(records.size.toDouble())
                log.error(e) { "Failed to process batch of ${records.size} records" }
                // 실패 시 커밋하지 않음 → Consumer 재시작 시 재처리됨
                // DLQ 전송은 BulkIndexer에서 처리됨
            } finally {
                consumeTimer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS)
            }
        }
    }

    // Dispatcher의 생명주기는 DispatcherConfig에서 관리합니다.
}
