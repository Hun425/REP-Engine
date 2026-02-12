package com.rep.consumer.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * WebClient 설정
 *
 * Embedding Service 호출을 위한 WebClient 빈을 생성합니다.
 */
@Configuration
class WebClientConfig(
    private val embeddingProperties: EmbeddingProperties
) {

    @Bean
    fun embeddingWebClient(builder: WebClient.Builder): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, embeddingProperties.timeoutMs.toInt())
            .responseTimeout(Duration.ofMillis(embeddingProperties.timeoutMs))
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(embeddingProperties.timeoutMs, TimeUnit.MILLISECONDS))
                conn.addHandlerLast(WriteTimeoutHandler(embeddingProperties.timeoutMs, TimeUnit.MILLISECONDS))
            }

        return builder
            .baseUrl(embeddingProperties.url)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
