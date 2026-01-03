package com.rep.consumer.service

import com.rep.consumer.config.ConsumerProperties
import com.rep.event.user.UserActionEvent
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = KotlinLogging.logger {}

/**
 * Dead Letter Queue Producer
 *
 * 처리 실패한 메시지를 DLQ 토픽으로 전송합니다.
 * DLQ 전송도 실패할 경우 로컬 파일에 기록합니다 (최후의 수단).
 *
 * @see <a href="docs/phase 2.md">Phase 2: DLQ 처리</a>
 */
@Component
class DlqProducer(
    private val kafkaTemplate: KafkaTemplate<String, UserActionEvent>,
    private val properties: ConsumerProperties,
    private val meterRegistry: MeterRegistry
) {
    // 파일 로테이션 동기화를 위한 Lock
    private val fileLock = ReentrantLock()

    private val dlqCounter: Counter = Counter.builder("kafka.dlq.sent")
        .tag("topic", properties.dlqTopic)
        .register(meterRegistry)

    private val dlqFailedCounter: Counter = Counter.builder("kafka.dlq.failed")
        .tag("topic", properties.dlqTopic)
        .register(meterRegistry)

    /**
     * 실패한 이벤트를 DLQ 토픽으로 동기 전송합니다.
     * 전송 완료를 보장하여 메시지 유실을 방지합니다.
     *
     * @return 전송 성공 여부
     */
    fun sendSync(event: UserActionEvent): Boolean {
        log.warn { "Sending event to DLQ: traceId=${event.traceId}" }

        return try {
            // 동기 전송 - 완료될 때까지 대기
            val result = kafkaTemplate.send(properties.dlqTopic, event.userId.toString(), event).get()
            dlqCounter.increment()
            log.info { "Event sent to DLQ successfully: traceId=${event.traceId}, offset=${result.recordMetadata.offset()}" }
            true
        } catch (e: Exception) {
            dlqFailedCounter.increment()
            log.error(e) { "Failed to send event to DLQ: traceId=${event.traceId}" }
            // 최후의 수단: 로컬 파일에 기록
            writeToFailedEventsFile(event)
            false
        }
    }

    /**
     * DLQ 전송도 실패한 경우 로컬 파일에 기록합니다.
     * 이 파일은 수동 복구에 사용됩니다.
     *
     * 파일 로테이션:
     * - 일별 파일 생성 (failed_events_YYYY-MM-DD.log)
     * - 설정된 크기 초과 시 타임스탬프 붙여서 백업
     *
     * 스레드 안전성: ReentrantLock으로 파일 로테이션 동기화
     */
    private fun writeToFailedEventsFile(event: UserActionEvent) {
        fileLock.withLock {
            try {
                val logsDir = File(properties.dlqLogsDir)
                if (!logsDir.exists()) {
                    if (!logsDir.mkdirs()) {
                        log.error { "Failed to create logs directory: ${properties.dlqLogsDir}" }
                        return
                    }
                }

                val dateStr = LocalDate.now().toString()
                val failedEventsFile = File(logsDir, "failed_events_$dateStr.log")

                // 파일 크기 제한 - 설정된 크기 초과 시 백업
                if (failedEventsFile.exists() && failedEventsFile.length() > properties.dlqFileMaxSizeBytes) {
                    val backupFile = File(logsDir, "failed_events_${dateStr}_${System.currentTimeMillis()}.log")
                    if (failedEventsFile.renameTo(backupFile)) {
                        log.info { "Rotated failed_events file to ${backupFile.name}" }
                    } else {
                        log.warn { "Failed to rotate failed_events file" }
                    }
                }

                val logEntry = "${Instant.now()}|${event.traceId}|${event.userId}|${event.productId}|${event.actionType}\n"
                failedEventsFile.appendText(logEntry)
                log.warn { "Event written to ${failedEventsFile.name}: traceId=${event.traceId}" }
            } catch (e: Exception) {
                log.error(e) { "Failed to write event to failed_events.log: traceId=${event.traceId}" }
            }
        }
    }
}
