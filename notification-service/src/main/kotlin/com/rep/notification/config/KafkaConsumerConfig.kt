package com.rep.notification.config

import com.rep.event.notification.NotificationEvent
import com.rep.event.product.ProductInventoryEvent
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties

/**
 * Kafka Consumer 설정
 *
 * 두 종류의 Consumer를 지원합니다:
 * 1. InventoryEvent Consumer: product.inventory.v1 토픽
 * 2. Notification Consumer: notification.push.v1 토픽
 */
@Configuration
class KafkaConsumerConfig {

    @Value("\${spring.kafka.bootstrap-servers}")
    private lateinit var bootstrapServers: String

    @Value("\${spring.kafka.consumer.properties.schema.registry.url}")
    private lateinit var schemaRegistryUrl: String

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
     * Inventory Event Listener Container Factory
     */
    @Bean
    fun inventoryListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, ProductInventoryEvent> {
        return ConcurrentKafkaListenerContainerFactory<String, ProductInventoryEvent>().apply {
            consumerFactory = inventoryConsumerFactory()
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            isBatchListener = false  // 단건 처리
            setConcurrency(3)  // product.inventory.v1 파티션 수
        }
    }

    /**
     * Notification Event Listener Container Factory (Push Sender용)
     */
    @Bean
    fun notificationListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> {
        return ConcurrentKafkaListenerContainerFactory<String, NotificationEvent>().apply {
            consumerFactory = notificationConsumerFactory()
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            isBatchListener = false  // 단건 처리
            setConcurrency(6)  // notification.push.v1 파티션 수
        }
    }
}
