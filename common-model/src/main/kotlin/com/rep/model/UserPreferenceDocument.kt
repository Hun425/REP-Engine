package com.rep.model

/**
 * ES user_preference_index 문서 구조
 *
 * 유저 취향 벡터의 ES 백업용 문서입니다.
 * Redis가 Primary이고, ES는 Redis 미스 시 폴백 용도입니다.
 *
 * @see docs/adr-004-vector-storage.md
 */
data class UserPreferenceDocument(
    val userId: String? = null,
    val vector: List<Float>? = null,
    val actionCount: Int? = null,
    val updatedAt: Long? = null
)
