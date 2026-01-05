package com.rep.notification.config

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private val log = KotlinLogging.logger {}

/**
 * Elasticsearch 클라이언트 설정
 */
@Configuration
class ElasticsearchConfig {

    @Value("\${spring.elasticsearch.uris:http://localhost:9200}")
    private lateinit var elasticsearchUri: String

    @Bean
    fun elasticsearchClient(objectMapper: ObjectMapper): ElasticsearchClient {
        log.info { "Initializing Elasticsearch client: $elasticsearchUri" }

        val httpHost = HttpHost.create(elasticsearchUri)
        val restClient = RestClient.builder(httpHost).build()
        val transport = RestClientTransport(restClient, JacksonJsonpMapper(objectMapper))

        return ElasticsearchClient(transport)
    }
}
