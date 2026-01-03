package com.rep.recommendation.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 추천 API 응답
 */
data class RecommendationResponse(
    val userId: String,
    val recommendations: List<ProductRecommendation>,
    val strategy: String,  // "knn" | "popularity" | "category_best"
    val latencyMs: Long
)

/**
 * 추천 상품 정보
 */
data class ProductRecommendation(
    val productId: String,
    val productName: String,
    val category: String,
    val price: Float,
    val score: Double = 0.0
)

/**
 * Redis에 저장된 유저 취향 데이터
 */
data class UserPreferenceData(
    val vector: List<Float>,
    val actionCount: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toFloatArray(): FloatArray = vector.toFloatArray()
}

/**
 * ES product_index 문서 구조
 */
data class ProductDocument(
    val productId: String? = null,
    val productName: String? = null,
    val category: String? = null,
    val price: Float? = null,
    val stock: Int? = null,
    val brand: String? = null,
    val description: String? = null,
    val productVector: List<Double>? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

/**
 * ES user_preference_index 문서 구조
 */
data class UserPreferenceDocument(
    val userId: String? = null,
    val preferenceVector: List<Double>? = null,
    val actionCount: Int? = null,
    val lastUpdated: Long? = null
)
