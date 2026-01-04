package com.rep.model

/**
 * ES product_index 문서 구조
 *
 * 상품 정보와 상품 벡터를 저장합니다.
 * KNN 검색 대상이 되는 핵심 인덱스입니다.
 *
 * @see docs/phase%203.md - product_index 매핑
 */
data class ProductDocument(
    val productId: String? = null,
    val productName: String? = null,
    val category: String? = null,
    val subCategory: String? = null,
    val price: Float? = null,
    val stock: Int? = null,
    val brand: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val productVector: List<Float>? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)
