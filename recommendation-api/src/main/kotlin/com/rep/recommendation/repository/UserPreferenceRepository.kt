package com.rep.recommendation.repository

import co.elastic.clients.elasticsearch.ElasticsearchClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rep.model.UserPreferenceData
import com.rep.model.UserPreferenceDocument
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * 유저 취향 벡터 Repository (조회 전용)
 *
 * Redis에서 유저 취향 벡터를 조회하고, 없으면 ES에서 폴백합니다.
 *
 * @see docs/adr-004-vector-storage.md
 */
@Repository
class UserPreferenceRepository(
    private val redisTemplate: ReactiveRedisTemplate<String, String>,
    private val esClient: ElasticsearchClient,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry
) {
    companion object {
        private const val KEY_PREFIX = "user:preference:"
        private const val ES_INDEX = "user_preference_index"
        private val TTL = Duration.ofHours(24)
        private const val REDIS_TIMEOUT_MS = 500L  // Redis 비정상 시 빠른 실패용
    }

    private val cacheHitCounter = Counter.builder("preference.cache.hit")
        .description("User preference cache hits")
        .register(meterRegistry)

    private val cacheMissCounter = Counter.builder("preference.cache.miss")
        .description("User preference cache misses")
        .register(meterRegistry)

    private val esFallbackCounter = Counter.builder("preference.es.fallback")
        .description("ES fallback for user preference")
        .register(meterRegistry)

    /**
     * 유저 취향 벡터를 조회합니다.
     *
     * 1. Redis에서 먼저 조회
     * 2. Redis 미스 시 ES에서 폴백 후 Redis에 캐싱
     *
     * @param userId 유저 ID
     * @return 취향 벡터 또는 null (Cold Start)
     */
    suspend fun get(userId: String): FloatArray? {
        val key = "$KEY_PREFIX$userId"

        return try {
            // 1. Redis에서 조회 (타임아웃 적용)
            val cached = withTimeoutOrNull(REDIS_TIMEOUT_MS) {
                redisTemplate.opsForValue().get(key).awaitSingleOrNull()
            }

            if (cached != null) {
                log.debug { "Cache hit for userId=$userId" }
                cacheHitCounter.increment()
                objectMapper.readValue<UserPreferenceData>(cached).toFloatArray()
            } else {
                // 2. Redis 미스 → ES에서 폴백
                log.debug { "Cache miss for userId=$userId, trying ES fallback" }
                cacheMissCounter.increment()
                val esResult = getFromEs(userId)
                if (esResult != null) {
                    esFallbackCounter.increment()
                    val (vector, actionCount) = esResult
                    // Redis에 캐싱하여 다음 조회 시 성능 향상
                    cacheToRedis(userId, vector, actionCount)
                    vector
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to get preference vector for userId=$userId" }
            null
        }
    }

    /**
     * ES에서 조회한 벡터를 Redis에 캐싱합니다.
     */
    private suspend fun cacheToRedis(userId: String, vector: FloatArray, actionCount: Int) {
        try {
            val key = "$KEY_PREFIX$userId"
            val data = UserPreferenceData(
                preferenceVector = vector.toList(),
                actionCount = actionCount,
                updatedAt = System.currentTimeMillis()
            )
            // 캐싱 실패해도 ES 폴백 결과는 반환하므로 타임아웃으로 빠르게 포기
            withTimeoutOrNull(REDIS_TIMEOUT_MS) {
                redisTemplate.opsForValue()
                    .set(key, objectMapper.writeValueAsString(data), TTL)
                    .awaitSingleOrNull()
            }
            log.debug { "Cached preference vector from ES fallback for userId=$userId" }
        } catch (e: Exception) {
            log.warn(e) { "Failed to cache preference vector for userId=$userId" }
        }
    }

    /**
     * ES에서 유저 취향 벡터와 actionCount를 조회합니다.
     *
     * @return Pair(벡터, actionCount) 또는 null
     */
    private fun getFromEs(userId: String): Pair<FloatArray, Int>? {
        return try {
            val response = esClient.get(
                { g -> g.index(ES_INDEX).id(userId) },
                UserPreferenceDocument::class.java
            )

            if (response.found()) {
                val source = response.source()
                val vector = source?.preferenceVector?.toFloatArray()
                val actionCount = source?.actionCount ?: 1

                if (vector != null) {
                    Pair(vector, actionCount)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to get preference vector from ES for userId=$userId" }
            null
        }
    }
}
