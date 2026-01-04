package com.rep.recommendation.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 추천 설정
 */
@Validated
@ConfigurationProperties(prefix = "recommendation")
data class RecommendationProperties(
    @field:Valid
    val knn: KnnProperties = KnnProperties(),

    @field:Valid
    val cache: CacheProperties = CacheProperties()
)

data class KnnProperties(
    @field:Positive(message = "k must be positive")
    val k: Int = 10
    // numCandidates는 k * 10으로 동적 계산됨 (docs/phase 3.md 참고)
)

data class CacheProperties(
    @field:Positive(message = "popularTtlMinutes must be positive")
    val popularTtlMinutes: Long = 10,

    @field:Positive(message = "globalCacheSize must be positive")
    val globalCacheSize: Int = 100,

    @field:Positive(message = "categoryCacheSize must be positive")
    val categoryCacheSize: Int = 50
)
