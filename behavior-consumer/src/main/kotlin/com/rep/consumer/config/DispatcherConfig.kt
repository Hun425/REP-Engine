package com.rep.consumer.config

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
 * Kafka Listener 등에서 Virtual Thread를 활용한 비동기 처리를 위해
 * Singleton으로 관리되는 Dispatcher를 제공합니다.
 *
 * @see docs/adr-001-concurrency-strategy.md
 */
@Configuration
@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherConfig {

    private var dispatcher: CloseableCoroutineDispatcher? = null

    /**
     * Virtual Thread 기반 Coroutine Dispatcher
     *
     * Java 25 Virtual Threads를 활용하여 blocking I/O 호출 시에도
     * 시스템 처리량을 유지합니다.
     */
    @Bean
    @Qualifier("virtualThreadDispatcher")
    fun virtualThreadDispatcher(): CloseableCoroutineDispatcher {
        log.info { "Creating Virtual Thread Coroutine Dispatcher" }
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
