package com.rep.recommendation.repository

import co.elastic.clients.elasticsearch.ElasticsearchClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rep.model.UserPreferenceData
import com.rep.model.UserPreferenceDocument
import kotlinx.coroutines.reactor.awaitSingleOrNull
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
    private val objectMapper: ObjectMapper
) {
    companion object {
        private const val KEY_PREFIX = "user:preference:"
        private const val ES_INDEX = "user_preference_index"
        private val TTL = Duration.ofHours(24)
    }

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
            // 1. Redis에서 조회
            val cached = redisTemplate.opsForValue().get(key).awaitSingleOrNull()

            if (cached != null) {
                log.debug { "Cache hit for userId=$userId" }
                objectMapper.readValue<UserPreferenceData>(cached).toFloatArray()
            } else {
                // 2. Redis 미스 → ES에서 폴백
                log.debug { "Cache miss for userId=$userId, trying ES fallback" }
                getFromEs(userId)?.also { (vector, actionCount) ->
                    // Redis에 캐싱하여 다음 조회 시 성능 향상
                    cacheToRedis(userId, vector, actionCount)
                }?.first
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
            redisTemplate.opsForValue()
                .set(key, objectMapper.writeValueAsString(data), TTL)
                .awaitSingleOrNull()
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
