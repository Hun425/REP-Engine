package com.rep.consumer.client

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

private val log = KotlinLogging.logger {}

/**
 * Embedding Service 클라이언트
 *
 * Python Embedding Service를 호출하여 텍스트를 384차원 벡터로 변환합니다.
 *
 * @see docs/adr-003-embedding-model.md
 */
@Component
class EmbeddingClient(
    private val embeddingWebClient: WebClient
) {
    companion object {
        const val QUERY_PREFIX = "query: "      // 검색 쿼리용 (유저 취향)
        const val PASSAGE_PREFIX = "passage: "  // 문서용 (상품 정보)
    }

    /**
     * 텍스트 목록을 벡터로 변환합니다.
     *
     * @param texts 변환할 텍스트 목록
     * @param prefix e5 모델용 prefix (query: 또는 passage:)
     * @return 벡터 목록 (각 벡터는 384차원)
     */
    suspend fun embed(texts: List<String>, prefix: String = QUERY_PREFIX): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()

        return try {
            val response = embeddingWebClient.post()
                .uri("/embed")
                .bodyValue(EmbedRequest(texts = texts, prefix = prefix))
                .retrieve()
                .bodyToMono<EmbedResponse>()
                .awaitSingleOrNull()

            response?.embeddings?.map { it.toFloatArray() }
        } catch (e: Exception) {
            log.error(e) { "Failed to call embedding service for ${texts.size} texts" }
            null
        }
    }

    /**
     * 단일 텍스트를 벡터로 변환합니다.
     */
    suspend fun embedSingle(text: String, prefix: String = QUERY_PREFIX): FloatArray? {
        return embed(listOf(text), prefix)?.firstOrNull()
    }

    /**
     * 헬스체크
     */
    suspend fun healthCheck(): Boolean {
        return try {
            val response = embeddingWebClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono<HealthResponse>()
                .awaitSingleOrNull()

            response?.status == "ok"
        } catch (e: Exception) {
            log.warn(e) { "Embedding service health check failed" }
            false
        }
    }
}

data class EmbedRequest(
    val texts: List<String>,
    val prefix: String = "query: "
)

data class EmbedResponse(
    val embeddings: List<List<Float>>,
    val dims: Int = 384
)

data class HealthResponse(
    val status: String,
    val model: String,
    val dims: Int
)
