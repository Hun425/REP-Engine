package com.rep.recommendation.model

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
