package com.rep.consumer.repository

import co.elastic.clients.elasticsearch.ElasticsearchClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * 유저 취향 벡터 Repository
 *
 * Redis (Primary) + Elasticsearch (Backup) Hybrid 저장소입니다.
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

    // ES 백업을 비동기로 처리하기 위한 CoroutineScope
    private val esBackupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @PreDestroy
    fun cleanup() {
        log.info { "Cleaning up UserPreferenceRepository ES backup scope..." }
        esBackupScope.cancel()
    }

    /**
     * 유저 취향 벡터를 저장합니다.
     *
     * 1. Redis에 즉시 저장 (Primary)
     * 2. ES에 비동기 백업 (best-effort, Redis가 Primary)
     *
     * 주의: Redis와 ES 저장은 원자적이지 않습니다.
     * Redis 저장 성공 후 ES 백업이 실패해도 Redis 데이터는 유지됩니다.
     * ES는 캐시 미스 시 fallback 용도로 사용되며, 24시간 내 갱신됩니다.
     *
     * @param userId 유저 ID
     * @param vector 취향 벡터 (384차원)
     * @param actionCount 누적 행동 수
     */
    suspend fun save(userId: String, vector: FloatArray, actionCount: Int = 1) {
        val key = "$KEY_PREFIX$userId"
        val data = UserPreferenceData(
            vector = vector.toList(),
            actionCount = actionCount,
            updatedAt = System.currentTimeMillis()
        )

        try {
            // 1. Redis 저장 (Primary)
            redisTemplate.opsForValue()
                .set(key, objectMapper.writeValueAsString(data), TTL)
                .awaitSingle()

            log.debug { "Saved preference vector for userId=$userId to Redis" }

            // 2. ES 백업 (비동기 - best-effort)
            // Redis가 Primary이므로 ES 백업 실패는 전체 저장 실패로 처리하지 않음
            esBackupScope.launch {
                try {
                    saveToEs(userId, vector, actionCount)
                } catch (e: Exception) {
                    log.warn(e) { "ES backup failed for userId=$userId (best-effort, Redis is primary)" }
                }
            }

        } catch (e: Exception) {
            log.error(e) { "Failed to save preference vector for userId=$userId to Redis" }
            throw e
        }
    }

    /**
     * 유저 취향 벡터를 조회합니다.
     *
     * 1. Redis에서 먼저 조회
     * 2. Redis 미스 시 ES에서 복구
     *
     * @param userId 유저 ID
     * @return 취향 벡터 또는 null
     */
    suspend fun get(userId: String): FloatArray? {
        val key = "$KEY_PREFIX$userId"

        return try {
            // 1. Redis에서 조회
            val cached = redisTemplate.opsForValue().get(key).awaitSingleOrNull()

            if (cached != null) {
                log.debug { "Cache hit for userId=$userId" }
                objectMapper.readValue<UserPreferenceData>(cached).vector.toFloatArray()
            } else {
                // 2. Redis 미스 → ES에서 복구
                log.debug { "Cache miss for userId=$userId, trying ES fallback" }
                getFromEs(userId)?.also { (vector, actionCount) ->
                    // Redis에 다시 캐싱 (actionCount 보존)
                    save(userId, vector, actionCount)
                }?.first  // Pair에서 vector만 반환
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to get preference vector for userId=$userId" }
            null
        }
    }

    /**
     * 유저 취향 데이터 전체를 조회합니다 (벡터 + 메타데이터).
     */
    suspend fun getWithMetadata(userId: String): UserPreferenceData? {
        val key = "$KEY_PREFIX$userId"

        return try {
            val cached = redisTemplate.opsForValue().get(key).awaitSingleOrNull()
            cached?.let { objectMapper.readValue<UserPreferenceData>(it) }
        } catch (e: Exception) {
            log.error(e) { "Failed to get preference data for userId=$userId" }
            null
        }
    }

    /**
     * ES에 유저 취향 벡터를 백업합니다.
     */
    private fun saveToEs(userId: String, vector: FloatArray, actionCount: Int) {
        try {
            val document = mapOf(
                "userId" to userId,
                "preferenceVector" to vector.toList(),
                "actionCount" to actionCount,
                "lastUpdated" to System.currentTimeMillis()
            )

            esClient.index { idx ->
                idx.index(ES_INDEX)
                    .id(userId)
                    .document(document)
            }

            log.debug { "Backed up preference vector for userId=$userId to ES" }
        } catch (e: Exception) {
            // ES 백업 실패는 로그만 남기고 계속 진행 (Redis가 Primary)
            log.warn(e) { "Failed to backup preference vector to ES for userId=$userId" }
        }
    }

    /**
     * ES에서 유저 취향 벡터와 actionCount를 조회합니다.
     *
     * @return Pair(vector, actionCount) 또는 null
     */
    private fun getFromEs(userId: String): Pair<FloatArray, Int>? {
        return try {
            val response = esClient.get(
                { g -> g.index(ES_INDEX).id(userId) },
                UserPreferenceDocument::class.java
            )

            if (response.found()) {
                val source = response.source()
                val vector = source?.preferenceVector?.map { it.toFloat() }?.toFloatArray()
                val actionCount = source?.actionCount ?: 1

                vector?.let { Pair(it, actionCount) }
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to get preference vector from ES for userId=$userId" }
            null
        }
    }
}

/**
 * Redis에 저장되는 유저 취향 데이터
 */
data class UserPreferenceData(
    val vector: List<Float>,
    val actionCount: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toFloatArray(): FloatArray = vector.toFloatArray()
}

/**
 * ES user_preference_index 문서 구조
 */
data class UserPreferenceDocument(
    val userId: String? = null,
    val preferenceVector: List<Double>? = null,
    val actionCount: Int? = null,
    val lastUpdated: Long? = null
)
