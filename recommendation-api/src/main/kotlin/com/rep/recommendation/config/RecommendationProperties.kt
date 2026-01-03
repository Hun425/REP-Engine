package com.rep.recommendation.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 추천 설정
 */
@ConfigurationProperties(prefix = "recommendation")
data class RecommendationProperties(
    val knn: KnnProperties = KnnProperties(),
    val cache: CacheProperties = CacheProperties()
)

data class KnnProperties(
    val k: Int = 10,
    val numCandidates: Int = 100
)

data class CacheProperties(
    val popularTtlMinutes: Long = 10
)
