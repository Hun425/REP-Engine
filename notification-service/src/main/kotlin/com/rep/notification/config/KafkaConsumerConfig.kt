package com.rep.notification.config

import com.rep.event.notification.NotificationEvent
import com.rep.event.product.ProductInventoryEvent
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig
import io.confluent.kafka.serializers.KafkaAvroSerializer
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

private val log = KotlinLogging.logger {}

/**
 * Kafka Consumer 설정
 *
 * 두 종류의 Consumer를 지원합니다:
 * 1. InventoryEvent Consumer: product.inventory.v1 토픽
 * 2. Notification Consumer: notification.push.v1 토픽
 *
 * DLQ (Dead Letter Queue) 지원:
 * - 재시도 후에도 실패한 메시지는 DLQ 토픽으로 이동
 * - DLQ 토픽명: 원본토픽.dlq (예: product.inventory.v1.dlq)
 */
@Configuration
class KafkaConsumerConfig(
    private val properties: NotificationProperties,
    private val meterRegistry: MeterRegistry
) {

    @Value("\${spring.kafka.bootstrap-servers}")
    private lateinit var bootstrapServers: String

    @Value("\${spring.kafka.consumer.properties.schema.registry.url}")
    private lateinit var schemaRegistryUrl: String

    // DLQ 메트릭
    private val dlqSentCounter by lazy {
        Counter.builder("kafka.dlq.sent")
            .description("Messages sent to DLQ")
            .register(meterRegistry)
    }

    /**
     * ProductInventoryEvent Consumer Factory
     */
    @Bean
    fun inventoryConsumerFactory(): ConsumerFactory<String, ProductInventoryEvent> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to KafkaAvroDeserializer::class.java,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 100,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl,
            KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG to true
        )
        return DefaultKafkaConsumerFactory(props)
    }

    /**
     * NotificationEvent Consumer Factory
     */
    @Bean
    fun notificationConsumerFactory(): ConsumerFactory<String, NotificationEvent> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to KafkaAvroDeserializer::class.java,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 100,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl,
            KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG to true
        )
        return DefaultKafkaConsumerFactory(props)
    }

    /**
     * DLQ용 KafkaTemplate (Avro 직렬화)
     */
    @Bean
    @ConditionalOnProperty("notification.dlq.enabled", havingValue = "true", matchIfMissing = true)
    fun dlqKafkaTemplate(): KafkaTemplate<String, Any> {
        val props = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to KafkaAvroSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.RETRIES_CONFIG to 3,
            KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl,
            // DLQ에서는 스키마 자동 등록 허용 (같은 스키마지만 토픽이 다름)
            KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS to true
        )
        val producerFactory = DefaultKafkaProducerFactory<String, Any>(props)
        return KafkaTemplate(producerFactory)
    }

    /**
     * DLQ 발행 recoverer
     *
     * 실패한 메시지를 원본토픽.dlq 토픽으로 전송
     */
    @Bean
    @ConditionalOnProperty("notification.dlq.enabled", havingValue = "true", matchIfMissing = true)
    fun deadLetterPublishingRecoverer(
        dlqKafkaTemplate: KafkaTemplate<String, Any>
    ): DeadLetterPublishingRecoverer {
        return DeadLetterPublishingRecoverer(dlqKafkaTemplate) { record: ConsumerRecord<*, *>, ex: Exception ->
            val dlqTopic = record.topic() + properties.dlq.topicSuffix
            dlqSentCounter.increment()
            log.error(ex) {
                "Sending to DLQ: topic=$dlqTopic, key=${record.key()}, " +
                    "partition=${record.partition()}, offset=${record.offset()}"
            }
            TopicPartition(dlqTopic, record.partition())
        }
    }

    /**
     * DLQ 지원 Error Handler
     *
     * 설정된 횟수만큼 재시도 후 실패 시 DLQ로 전송
     */
    @Bean
    @ConditionalOnProperty("notification.dlq.enabled", havingValue = "true", matchIfMissing = true)
    fun kafkaErrorHandler(
        deadLetterPublishingRecoverer: DeadLetterPublishingRecoverer
    ): CommonErrorHandler {
        val backOff = FixedBackOff(
            properties.dlq.retryBackoffMs,
            properties.dlq.maxRetries.toLong()
        )

        return DefaultErrorHandler(deadLetterPublishingRecoverer, backOff).apply {
            // 재시도하지 않을 예외 타입 설정 (필요시)
            // addNotRetryableExceptions(IllegalArgumentException::class.java)

            setRetryListeners({ record, ex, deliveryAttempt ->
                log.warn {
                    "Retry attempt $deliveryAttempt for record: " +
                        "topic=${record.topic()}, key=${record.key()}"
                }
            })
        }
    }

    /**
     * DLQ 비활성화 시 기본 Error Handler
     */
    @Bean
    @ConditionalOnProperty("notification.dlq.enabled", havingValue = "false")
    fun simpleErrorHandler(): CommonErrorHandler {
        return DefaultErrorHandler().apply {
            // 재시도 없이 로깅만 하고 커밋
            setBackOffFunction { _, _ -> FixedBackOff(0, 0) }
        }
    }

    /**
     * Inventory Event Listener Container Factory
     */
    @Bean
    fun inventoryListenerContainerFactory(
        errorHandler: CommonErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, ProductInventoryEvent> {
        return ConcurrentKafkaListenerContainerFactory<String, ProductInventoryEvent>().apply {
            consumerFactory = inventoryConsumerFactory()
            containerProperties.ackMode = ContainerProperties.AckMode.RECORD
            setCommonErrorHandler(errorHandler)
            isBatchListener = false
            setConcurrency(3)  // product.inventory.v1 파티션 수
        }
    }

    /**
     * Notification Event Listener Container Factory (Push Sender용)
     */
    @Bean
    fun notificationListenerContainerFactory(
        errorHandler: CommonErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> {
        return ConcurrentKafkaListenerContainerFactory<String, NotificationEvent>().apply {
            consumerFactory = notificationConsumerFactory()
            containerProperties.ackMode = ContainerProperties.AckMode.RECORD
            setCommonErrorHandler(errorHandler)
            isBatchListener = false
            setConcurrency(6)  // notification.push.v1 파티션 수
        }
    }
}
