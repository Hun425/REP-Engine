package com.rep.simulator.config

import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Schema Registry 헬스 체크
 *
 * Actuator /health 엔드포인트에서 Schema Registry 연결 상태를 확인합니다.
 * Schema Registry URL이 설정된 경우에만 활성화됩니다.
 *
 * simulator 모듈은 WebFlux 의존성이 없으므로 Java HttpClient를 사용합니다.
 *
 * @see docs/phase%205.md
 */
@Component
@ConditionalOnProperty(name = ["spring.kafka.producer.properties.schema.registry.url"])
class SchemaRegistryHealthIndicator(
    @Value("\${spring.kafka.producer.properties.schema.registry.url}")
    private val schemaRegistryUrl: String
) : HealthIndicator {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    override fun health(): Health {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$schemaRegistryUrl/subjects"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                Health.up()
                    .withDetail("url", schemaRegistryUrl)
                    .withDetail("status", "connected")
                    .build()
            } else {
                Health.down()
                    .withDetail("url", schemaRegistryUrl)
                    .withDetail("status", "HTTP ${response.statusCode()}")
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
