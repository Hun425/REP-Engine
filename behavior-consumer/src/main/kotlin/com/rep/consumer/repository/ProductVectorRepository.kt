package com.rep.consumer.repository

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.GetResponse
import mu.KotlinLogging
import org.springframework.stereotype.Repository

private val log = KotlinLogging.logger {}

/**
 * 상품 벡터 조회 Repository
 *
 * Elasticsearch의 product_index에서 상품 벡터를 조회합니다.
 *
 * @see docs/phase%203.md
 */
@Repository
class ProductVectorRepository(
    private val esClient: ElasticsearchClient
) {
    companion object {
        private const val INDEX_NAME = "product_index"
        private const val EXPECTED_DIMENSIONS = 384  // multilingual-e5-base
    }

    /**
     * 상품 ID로 상품 벡터를 조회합니다.
     *
     * @param productId 상품 ID
     * @return 상품 벡터 (384차원) 또는 null (상품이 없거나 벡터가 없는 경우)
     */
    fun getProductVector(productId: String): FloatArray? {
        return try {
            val response: GetResponse<ProductDocument> = esClient.get(
                { g -> g.index(INDEX_NAME).id(productId) },
                ProductDocument::class.java
            )

            if (response.found()) {
                val vector = response.source()?.productVector?.map { it.toFloat() }?.toFloatArray()

                // 벡터 차원 검증
                if (vector != null && vector.size != EXPECTED_DIMENSIONS) {
                    log.warn { "Product $productId has invalid vector dimension: ${vector.size}, expected: $EXPECTED_DIMENSIONS" }
                    return null
                }

                vector
            } else {
                log.debug { "Product not found: $productId" }
                null
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to get product vector for productId=$productId" }
            null
        }
    }

    /**
     * 여러 상품의 벡터를 한 번에 조회합니다.
     *
     * @param productIds 상품 ID 목록
     * @return 상품 ID → 벡터 맵
     */
    fun getProductVectors(productIds: List<String>): Map<String, FloatArray> {
        if (productIds.isEmpty()) return emptyMap()

        return try {
            val response = esClient.mget(
                { m -> m.index(INDEX_NAME).ids(productIds) },
                ProductDocument::class.java
            )

            response.docs()
                .filter { it.result()?.found() == true }
                .mapNotNull { doc ->
                    val id = doc.result()?.id() ?: return@mapNotNull null
                    val source = doc.result()?.source()
                    val vector = source?.productVector?.map { it.toFloat() }?.toFloatArray()

                    // 벡터 차원 검증
                    if (vector != null && vector.size != EXPECTED_DIMENSIONS) {
                        log.warn { "Product $id has invalid vector dimension: ${vector.size}, expected: $EXPECTED_DIMENSIONS" }
                        return@mapNotNull null
                    }

                    if (vector != null) {
                        id to vector
                    } else null
                }
                .toMap()
        } catch (e: Exception) {
            log.error(e) { "Failed to get product vectors for ${productIds.size} products" }
            emptyMap()
        }
    }
}

/**
 * Elasticsearch product_index 문서 구조
 */
data class ProductDocument(
    val productId: String? = null,
    val productName: String? = null,
    val category: String? = null,
    val price: Float? = null,
    val stock: Int? = null,
    val brand: String? = null,
    val description: String? = null,
    val productVector: List<Double>? = null,  // ES에서는 double로 저장됨
    val createdAt: String? = null,
    val updatedAt: String? = null
)
