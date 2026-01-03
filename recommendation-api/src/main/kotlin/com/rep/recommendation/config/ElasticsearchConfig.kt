package com.rep.recommendation.config

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private val log = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "elasticsearch")
data class ElasticsearchProperties(
    val host: String = "localhost",
    val port: Int = 9200,
    val scheme: String = "http"
)

/**
 * Elasticsearch 설정
 */
@Configuration
class ElasticsearchConfig(
    private val properties: ElasticsearchProperties
) {
    private var restClient: RestClient? = null
    private var transport: RestClientTransport? = null

    @Bean
    fun elasticsearchClient(): ElasticsearchClient {
        restClient = RestClient.builder(
            HttpHost(properties.host, properties.port, properties.scheme)
        ).build()

        transport = RestClientTransport(restClient, JacksonJsonpMapper())

        log.info { "Elasticsearch client created: ${properties.scheme}://${properties.host}:${properties.port}" }

        return ElasticsearchClient(transport)
    }

    @PreDestroy
    fun cleanup() {
        log.info { "Cleaning up Elasticsearch connections..." }
        try {
            transport?.close()
            restClient?.close()
            log.info { "Elasticsearch connections closed successfully" }
        } catch (e: Exception) {
            log.error(e) { "Error closing Elasticsearch connections" }
        }
    }
}
