package com.rep.notification.service

import com.rep.event.notification.NotificationEvent
import com.rep.notification.config.NotificationProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * 알림 메시지 Kafka Producer
 *
 * notification.push.v1 토픽으로 알림 메시지를 발행합니다.
 *
 * @see docs/phase%204.md - Notification Producer
 */
@Component
class NotificationProducer(
    private val kafkaTemplate: KafkaTemplate<String, NotificationEvent>,
    private val properties: NotificationProperties,
    meterRegistry: MeterRegistry
) {
    private val sendSuccessCounter = Counter.builder("notification.send.success")
        .description("Notifications sent successfully")
        .register(meterRegistry)

    private val sendFailedCounter = Counter.builder("notification.send.failed")
        .description("Notifications failed to send")
        .register(meterRegistry)

    /**
     * 알림 메시지를 Kafka로 발행합니다.
     *
     * @param notification 알림 이벤트
     */
    fun send(notification: NotificationEvent) {
        kafkaTemplate.send(
            properties.notificationTopic,
            notification.userId.toString(),
            notification
        ).whenComplete { result, ex ->
            if (ex != null) {
                log.error(ex) { "Failed to send notification: ${notification.notificationId}" }
                sendFailedCounter.increment()
            } else {
                log.debug {
                    "Notification sent: ${notification.notificationId}, " +
                        "offset=${result.recordMetadata.offset()}"
                }
                sendSuccessCounter.increment()
            }
        }
    }
}
