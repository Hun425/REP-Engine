package com.rep.recommendation.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer

/**
 * Redis 설정 (Reactive)
 *
 * 유저 취향 벡터를 Redis에서 조회합니다.
 *
 * @see docs/adr-004-vector-storage.md
 */
@Configuration
class RedisConfig {

    @Bean
    fun reactiveRedisTemplate(
        connectionFactory: ReactiveRedisConnectionFactory
    ): ReactiveRedisTemplate<String, String> {
        val serializer = StringRedisSerializer()

        val context = RedisSerializationContext
            .newSerializationContext<String, String>(serializer)
            .key(serializer)
            .value(serializer)
            .hashKey(serializer)
            .hashValue(serializer)
            .build()

        return ReactiveRedisTemplate(connectionFactory, context)
    }
}
