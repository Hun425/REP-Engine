package com.rep.notification.config

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory

/**
 * ShedLock 분산 락 설정
 *
 * 다중 인스턴스 환경에서 @Scheduled 작업이 단일 인스턴스에서만 실행되도록 보장
 * - Redis 기반 분산 락 사용
 * - 기본 최대 락 유지 시간: 1시간 (장애 시 자동 해제)
 *
 * @see RecommendationScheduler
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "1h")
class ShedLockConfig {

    /**
     * Redis 기반 락 제공자
     *
     * @param connectionFactory Spring Boot 자동 구성된 Redis 연결 팩토리
     * @return Redis 락 제공자 (환경 이름: rep-notification)
     */
    @Bean
    fun lockProvider(connectionFactory: RedisConnectionFactory): LockProvider {
        return RedisLockProvider(connectionFactory, "rep-notification")
    }
}
