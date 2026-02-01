package com.rep.recommendation.config

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 비동기 처리 설정
 *
 * Java 25 Virtual Threads 기반 CoroutineDispatcher를 제공합니다.
 * Spring MVC의 blocking 엔드포인트에서 suspend 함수를 호출할 때 사용합니다.
 *
 * @see docs/adr-001-concurrency-strategy.md
 */
@Configuration
class AsyncConfig {

    private lateinit var virtualThreadExecutor: ExecutorService

    /**
     * Virtual Thread 기반 CoroutineDispatcher
     *
     * runBlocking에서 이 dispatcher를 사용하면:
     * - Virtual Thread 위에서 코루틴이 실행됨
     * - Blocking I/O 발생 시 Virtual Thread가 unmount되어 처리량 유지
     */
    @Bean
    fun virtualThreadDispatcher(): CoroutineDispatcher {
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()
        return virtualThreadExecutor.asCoroutineDispatcher()
    }

    @PreDestroy
    fun cleanup() {
        if (::virtualThreadExecutor.isInitialized) {
            virtualThreadExecutor.close()
        }
    }
}
