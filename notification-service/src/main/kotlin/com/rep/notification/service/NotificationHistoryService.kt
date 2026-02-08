package com.rep.notification.service

import co.elastic.clients.elasticsearch.ElasticsearchClient
import com.rep.event.notification.NotificationEvent
import com.rep.model.SendStatus
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * 알림 이력 저장 서비스
 *
 * ES notification_history_index에 알림 발송 이력을 저장합니다.
 *
 * @see docs/phase%204.md - 알림 이력 저장
 */
@Component
class NotificationHistoryService(
    private val esClient: ElasticsearchClient,
    meterRegistry: MeterRegistry
) {
    companion object {
        private const val INDEX_NAME = "notification_history_index"
    }

    private val saveSuccessCounter = Counter.builder("notification.history.save.success")
        .description("Notification history saved successfully")
        .register(meterRegistry)

    private val saveFailedCounter = Counter.builder("notification.history.save.failed")
        .description("Notification history save failures")
        .register(meterRegistry)

    /**
     * 알림 이력을 저장합니다.
     *
     * @param notification 알림 이벤트
     * @param status 발송 상태
     */
    fun save(notification: NotificationEvent, status: SendStatus) {
        try {
            val document = mapOf(
                "notificationId" to notification.notificationId.toString(),
                "userId" to notification.userId.toString(),
                "productId" to notification.productId.toString(),
                "type" to notification.notificationType.toString(),
                "title" to notification.title.toString(),
                "body" to notification.body.toString(),
                "data" to notification.data.mapKeys { it.key.toString() }.mapValues { it.value.toString() },
                "channels" to notification.channels.map { it.toString() },
                "priority" to notification.priority.toString(),
                "status" to status.name,
                "sentAt" to Instant.now().toString()  // ISO 8601 형식 (ES date 타입 호환)
            )

            esClient.index { i ->
                i.index(INDEX_NAME)
                    .id(notification.notificationId.toString())
                    .document(document)
            }

            log.debug { "Saved notification history: ${notification.notificationId}, status=$status" }
            saveSuccessCounter.increment()

        } catch (e: Exception) {
            log.error(e) { "Failed to save notification history: ${notification.notificationId}" }
            saveFailedCounter.increment()
        }
    }
}
