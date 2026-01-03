package com.rep.consumer.config

import com.rep.event.user.UserActionEvent
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

@Configuration
class KafkaConsumerConfig(
    private val consumerProperties: ConsumerProperties
) {

    @Value("\${spring.kafka.bootstrap-servers}")
    private lateinit var bootstrapServers: String

    @Value("\${spring.kafka.consumer.properties.schema.registry.url}")
    private lateinit var schemaRegistryUrl: String

    @Bean
    fun consumerFactory(): ConsumerFactory<String, UserActionEvent> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            // Group ID는 application.yml 또는 @KafkaListener에서 설정
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to KafkaAvroDeserializer::class.java,

            // 성능 튜닝 - Phase 2 문서 기준
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to consumerProperties.bulkSize,  // Bulk 크기와 일치
            ConsumerConfig.FETCH_MIN_BYTES_CONFIG to 1024,     // 최소 fetch 크기
            ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG to 500,    // 최대 대기 시간

            // 안정성 - 수동 커밋으로 메시지 유실 방지
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",

            // Avro Schema Registry
            KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl,
            KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG to true
        )
        return DefaultKafkaConsumerFactory(props)
    }

    @Bean
    fun kafkaListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, UserActionEvent> {
        return ConcurrentKafkaListenerContainerFactory<String, UserActionEvent>().apply {
            consumerFactory = consumerFactory()
            // 수동 커밋 - 배치 처리 완료 즉시 커밋
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            // 배치 처리 활성화
            isBatchListener = true
            containerProperties.idleBetweenPolls = 100
            // Concurrency 설정 - 병렬 Consumer 수
            setConcurrency(consumerProperties.concurrency)
        }
    }
}
