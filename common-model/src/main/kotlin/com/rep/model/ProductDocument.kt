package com.rep.model

/**
 * ES product_index 문서 구조
 *
 * 상품 정보와 상품 벡터를 저장합니다.
 * KNN 검색 대상이 되는 핵심 인덱스입니다.
 *
 * @property productId 상품 고유 ID (필수)
 * @property productName 상품명 (필수)
 * @property category 카테고리 (필수)
 * @property price 가격 (필수)
 *
 * @see docs/phase%203.md - product_index 매핑
 */
data class ProductDocument(
    // 필수 필드 (non-null with default for JSON deserialization)
    val productId: String = "",
    val productName: String = "",
    val category: String = "",
    val price: Float = 0f,

    // 선택 필드 (nullable)
    val subCategory: String? = null,
    val stock: Int? = null,
    val brand: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val productVector: List<Float>? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    /**
     * 필수 필드 유효성 검증
     */
    fun isValid(): Boolean {
        return productId.isNotBlank() &&
               productName.isNotBlank() &&
               category.isNotBlank() &&
               price >= 0
    }

    /**
     * KNN 검색 가능 여부 (벡터 존재 확인)
     */
    fun hasVector(): Boolean {
        return productVector != null && productVector.isNotEmpty()
    }
}
