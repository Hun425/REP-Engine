package com.rep.simulator.config

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private val log = KotlinLogging.logger {}

@Configuration
class ElasticsearchConfig {

    @Value("\${elasticsearch.host:localhost}")
    private lateinit var host: String

    @Value("\${elasticsearch.port:9200}")
    private var port: Int = 9200

    @Value("\${elasticsearch.scheme:http}")
    private lateinit var scheme: String

    private var restClient: RestClient? = null
    private var transport: RestClientTransport? = null

    @Bean
    fun restClient(): RestClient {
        return RestClient.builder(
            HttpHost(host, port, scheme)
        ).build().also { restClient = it }
    }

    @Bean
    fun elasticsearchClient(restClient: RestClient): ElasticsearchClient {
        val jsonpMapper = JacksonJsonpMapper()
        transport = RestClientTransport(restClient, jsonpMapper)
        return ElasticsearchClient(transport)
    }

    @PreDestroy
    fun cleanup() {
        log.info { "Cleaning up Elasticsearch connections..." }
        try {
            transport?.close()
            restClient?.close()
        } catch (e: Exception) {
            log.error(e) { "Error closing Elasticsearch connections" }
        }
    }
}
