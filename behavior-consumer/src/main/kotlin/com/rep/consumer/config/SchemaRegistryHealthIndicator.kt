package com.rep.consumer.config

import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Schema Registry 헬스 체크
 *
 * Actuator /health 엔드포인트에서 Schema Registry 연결 상태를 확인합니다.
 * Schema Registry URL이 설정된 경우에만 활성화됩니다.
 *
 * @see docs/phase%205.md
 */
@Component
@ConditionalOnProperty(name = ["spring.kafka.properties.schema.registry.url"])
class SchemaRegistryHealthIndicator(
    @Value("\${spring.kafka.properties.schema.registry.url}")
    private val schemaRegistryUrl: String
) : HealthIndicator {

    private val webClient = WebClient.builder()
        .baseUrl(schemaRegistryUrl)
        .build()

    override fun health(): Health {
        return try {
            val response = webClient.get()
                .uri("/subjects")
                .retrieve()
                .bodyToMono(String::class.java)
                .block(Duration.ofSeconds(5))

            if (response != null) {
                Health.up()
                    .withDetail("url", schemaRegistryUrl)
                    .withDetail("status", "connected")
                    .build()
            } else {
                Health.down()
                    .withDetail("url", schemaRegistryUrl)
                    .withDetail("status", "empty response")
                    .build()
            }
        } catch (e: Exception) {
            log.warn { "Schema Registry health check failed: ${e.message}" }
            Health.down()
                .withDetail("url", schemaRegistryUrl)
                .withDetail("error", e.message ?: "unknown error")
                .build()
        }
    }
}
