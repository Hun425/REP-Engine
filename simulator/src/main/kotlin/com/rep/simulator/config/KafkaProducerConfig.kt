package com.rep.simulator.config

import com.rep.event.user.UserActionEvent
import io.confluent.kafka.serializers.KafkaAvroSerializer
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

/**
 * Kafka Producer 설정
 *
 * application.yml의 spring.kafka.producer 설정을 사용합니다.
 * 설정값을 변경하려면 application.yml을 수정하세요.
 */
@Configuration
class KafkaProducerConfig(
    private val kafkaProperties: KafkaProperties
) {

    @Bean
    fun producerFactory(): ProducerFactory<String, UserActionEvent> {
        val configProps = mutableMapOf<String, Any>()

        // Spring Boot의 KafkaProperties에서 기본 설정 로드
        configProps[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaProperties.bootstrapServers
        configProps[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        configProps[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = KafkaAvroSerializer::class.java

        // application.yml의 spring.kafka.producer 설정 적용
        val producerProps = kafkaProperties.producer
        configProps[ProducerConfig.ACKS_CONFIG] = producerProps.acks ?: "all"
        configProps[ProducerConfig.RETRIES_CONFIG] = producerProps.retries ?: 3

        // application.yml의 spring.kafka.producer.properties 설정 적용
        producerProps.properties.forEach { (key, value) ->
            when (key) {
                "enable.idempotence" -> configProps[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG] = value.toBoolean()
                "linger.ms" -> configProps[ProducerConfig.LINGER_MS_CONFIG] = value.toInt()
                "batch.size" -> configProps[ProducerConfig.BATCH_SIZE_CONFIG] = value.toInt()
                "max.in.flight.requests.per.connection" -> configProps[ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION] = value.toInt()
                "schema.registry.url" -> configProps[KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG] = value
            }
        }

        return DefaultKafkaProducerFactory(configProps)
    }

    @Bean
    fun kafkaTemplate(): KafkaTemplate<String, UserActionEvent> {
        return KafkaTemplate(producerFactory())
    }
}
