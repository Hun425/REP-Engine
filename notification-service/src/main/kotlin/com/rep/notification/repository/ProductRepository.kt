package com.rep.notification.repository

import co.elastic.clients.elasticsearch.ElasticsearchClient
import com.rep.model.ProductDocument
import mu.KotlinLogging
import org.springframework.stereotype.Repository

private val log = KotlinLogging.logger {}

/**
 * 상품 정보 조회 Repository
 *
 * ES product_index에서 상품 정보를 조회합니다.
 * 알림 메시지 생성 시 상품명 등 정보가 필요합니다.
 */
@Repository
class ProductRepository(
    private val esClient: ElasticsearchClient
) {
    companion object {
        private const val INDEX_NAME = "product_index"
    }

    /**
     * 상품 ID로 상품 정보를 조회합니다.
     *
     * @param productId 상품 ID
     * @return 상품 정보 또는 null
     */
    fun findById(productId: String): ProductDocument? {
        return try {
            val response = esClient.get(
                { g -> g.index(INDEX_NAME).id(productId) },
                ProductDocument::class.java
            )

            if (response.found()) {
                response.source()
            } else {
                log.debug { "Product not found: $productId" }
                null
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to get product: $productId" }
            null
        }
    }
}
