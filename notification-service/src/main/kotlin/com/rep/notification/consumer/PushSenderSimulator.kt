package com.rep.notification.consumer

import com.rep.event.notification.Channel
import com.rep.event.notification.NotificationEvent
import com.rep.model.SendStatus
import com.rep.notification.service.NotificationHistoryService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * Push 발송 시뮬레이터
 *
 * notification.push.v1 토픽에서 알림 메시지를 수신하여
 * 각 채널별로 발송을 시뮬레이션합니다.
 *
 * 실제 운영에서는 FCM, APNs, SMS Gateway 등을 연동합니다.
 *
 * @see docs/phase%204.md - Push Sender
 */
@Component
class PushSenderSimulator(
    private val historyService: NotificationHistoryService,
    private val meterRegistry: MeterRegistry
) {
    private val sentCounter = Counter.builder("notification.push.sent")
        .description("Push notifications sent (simulated)")
        .register(meterRegistry)

    private val channelCounters = java.util.concurrent.ConcurrentHashMap<String, Counter>()

    @KafkaListener(
        topics = ["\${notification.notification-topic}"],
        groupId = "push-sender-group",
        containerFactory = "notificationListenerContainerFactory"
    )
    fun consume(
        record: ConsumerRecord<String, NotificationEvent>,
        acknowledgment: Acknowledgment
    ) {
        val notification = record.value()

        log.debug {
            "Received notification for sending: notificationId=${notification.notificationId}, " +
                "userId=${notification.userId}, type=${notification.notificationType}"
        }

        try {
            // 각 채널별로 발송 시뮬레이션
            notification.channels.forEach { channel ->
                sendToChannel(channel, notification)
                getChannelCounter(channel.toString()).increment()
            }

            // 이력 저장
            historyService.save(notification, SendStatus.SENT)

            sentCounter.increment()
            acknowledgment.acknowledge()

        } catch (e: Exception) {
            log.error(e) { "Failed to send notification: ${notification.notificationId}" }
            historyService.save(notification, SendStatus.FAILED)
            acknowledgment.acknowledge()
        }
    }

    private fun sendToChannel(channel: Channel, notification: NotificationEvent) {
        when (channel) {
            Channel.PUSH -> simulatePush(notification)
            Channel.SMS -> simulateSms(notification)
            Channel.EMAIL -> simulateEmail(notification)
            Channel.IN_APP -> simulateInApp(notification)
        }
    }

    private fun simulatePush(notification: NotificationEvent) {
        log.info {
            """
            |[PUSH] Sending to ${notification.userId}
            |  Title: ${notification.title}
            |  Body: ${notification.body}
            |  Data: ${notification.data}
            """.trimMargin()
        }
        // 실제 구현: FCM/APNs 호출
        // fcmClient.send(Message.builder()
        //     .setToken(userDeviceToken)
        //     .setNotification(...)
        //     .build())
    }

    private fun simulateSms(notification: NotificationEvent) {
        log.info { "[SMS] ${notification.userId}: ${notification.body}" }
        // 실제 구현: SMS Gateway 호출
    }

    private fun simulateEmail(notification: NotificationEvent) {
        log.info { "[EMAIL] ${notification.userId}: ${notification.title} - ${notification.body}" }
        // 실제 구현: SendGrid/SES 호출
    }

    private fun simulateInApp(notification: NotificationEvent) {
        log.info { "[IN_APP] ${notification.userId}: ${notification.title}" }
        // 실제 구현: WebSocket을 통해 실시간 전송
    }

    private fun getChannelCounter(channel: String): Counter {
        return channelCounters.getOrPut(channel) {
            Counter.builder("notification.push.channel")
                .tag("channel", channel)
                .description("Notifications sent per channel")
                .register(meterRegistry)
        }
    }
}
