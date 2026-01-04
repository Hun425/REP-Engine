package com.rep.model

/**
 * Redis에 저장되는 유저 취향 데이터
 *
 * @property preferenceVector 취향 벡터 (384차원, multilingual-e5-base)
 * @property actionCount 누적 행동 수
 * @property updatedAt 마지막 업데이트 시간 (epoch millis)
 *
 * @see docs/adr-004-vector-storage.md
 */
data class UserPreferenceData(
    val preferenceVector: List<Float>,
    val actionCount: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toFloatArray(): FloatArray = preferenceVector.toFloatArray()
}
