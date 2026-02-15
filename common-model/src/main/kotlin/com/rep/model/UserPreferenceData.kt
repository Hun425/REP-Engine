package com.rep.model

/**
 * Redis에 저장되는 유저 취향 데이터
 *
 * @property preferenceVector 취향 벡터 (multilingual-e5-base 768차원, consumer.vector-dimensions로 설정)
 * @property actionCount 누적 행동 수
 * @property updatedAt 마지막 업데이트 시간 (epoch millis)
 * @property version 버전 (Optimistic Locking용, ES 동기화 race condition 방지)
 *
 * @see docs/adr-004-vector-storage.md
 */
data class UserPreferenceData(
    val preferenceVector: List<Float>,
    val actionCount: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val version: Long = 1
) {
    companion object {
        const val VECTOR_DIMENSIONS = 768
    }

    init {
        require(preferenceVector.size == VECTOR_DIMENSIONS) {
            "preferenceVector must have $VECTOR_DIMENSIONS dimensions, got ${preferenceVector.size}"
        }
    }

    fun toFloatArray(): FloatArray = preferenceVector.toFloatArray()
}
