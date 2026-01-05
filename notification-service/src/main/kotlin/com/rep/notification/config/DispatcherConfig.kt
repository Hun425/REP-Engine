package com.rep.notification.config

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

/**
 * Virtual Thread 기반 Coroutine Dispatcher 설정
 *
 * @see docs/adr-001-concurrency-strategy.md
 */
@Configuration
@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherConfig {

    private var dispatcher: CloseableCoroutineDispatcher? = null

    @Bean
    @Qualifier("virtualThreadDispatcher")
    fun virtualThreadDispatcher(): CloseableCoroutineDispatcher {
        log.info { "Creating Virtual Thread Coroutine Dispatcher for notification-service" }
        return Executors.newVirtualThreadPerTaskExecutor()
            .asCoroutineDispatcher()
            .also { dispatcher = it }
    }

    @PreDestroy
    fun cleanup() {
        log.info { "Closing Virtual Thread Coroutine Dispatcher" }
        dispatcher?.close()
    }
}
