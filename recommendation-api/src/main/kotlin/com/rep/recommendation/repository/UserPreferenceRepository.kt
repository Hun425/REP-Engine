package com.rep.recommendation.repository

import co.elastic.clients.elasticsearch.ElasticsearchClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rep.recommendation.model.UserPreferenceData
import com.rep.recommendation.model.UserPreferenceDocument
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Repository

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
    }

    /**
     * 유저 취향 벡터를 조회합니다.
     *
     * 1. Redis에서 먼저 조회
     * 2. Redis 미스 시 ES에서 폴백
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
                getFromEs(userId)
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to get preference vector for userId=$userId" }
            null
        }
    }

    /**
     * ES에서 유저 취향 벡터를 조회합니다.
     */
    private fun getFromEs(userId: String): FloatArray? {
        return try {
            val response = esClient.get(
                { g -> g.index(ES_INDEX).id(userId) },
                UserPreferenceDocument::class.java
            )

            if (response.found()) {
                response.source()?.preferenceVector?.map { it.toFloat() }?.toFloatArray()
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to get preference vector from ES for userId=$userId" }
            null
        }
    }
}
