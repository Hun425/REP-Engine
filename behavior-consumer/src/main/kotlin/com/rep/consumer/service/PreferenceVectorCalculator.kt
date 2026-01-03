package com.rep.consumer.service

import mu.KotlinLogging
import org.springframework.stereotype.Component
import kotlin.math.sqrt

private val log = KotlinLogging.logger {}

/**
 * 유저 취향 벡터 계산기
 *
 * 지수 이동 평균(EMA)을 사용하여 유저 취향 벡터를 갱신합니다.
 *
 * 공식: New = Old × (1 - α) + Current × α
 *
 * α (alpha): 새로운 행동의 가중치
 * - VIEW: 0.1 (약한 신호)
 * - CLICK: 0.3 (중간 신호)
 * - PURCHASE: 0.5 (강한 신호)
 *
 * @see docs/phase%203.md
 */
@Component
class PreferenceVectorCalculator {

    companion object {
        // EMA 가중치 (Phase 3 문서 기준)
        const val ALPHA_VIEW = 0.1f
        const val ALPHA_SEARCH = 0.2f   // 검색은 중간 정도의 관심 신호
        const val ALPHA_CLICK = 0.3f
        const val ALPHA_PURCHASE = 0.5f

        // 벡터 차원 (multilingual-e5-base)
        const val EXPECTED_VECTOR_DIMENSIONS = 384
    }

    /**
     * 유저 취향 벡터를 갱신합니다.
     *
     * @param currentPreference 현재 취향 벡터 (null이면 신규 유저)
     * @param newProductVector 행동한 상품의 벡터
     * @param actionType 행동 유형 (VIEW, CLICK, PURCHASE 등)
     * @return 갱신된 취향 벡터 (정규화됨)
     */
    fun update(
        currentPreference: FloatArray?,
        newProductVector: FloatArray,
        actionType: String
    ): FloatArray {
        // 벡터 차원 검증
        require(newProductVector.size == EXPECTED_VECTOR_DIMENSIONS) {
            "Product vector dimension mismatch: expected=$EXPECTED_VECTOR_DIMENSIONS, actual=${newProductVector.size}"
        }
        if (currentPreference != null) {
            require(currentPreference.size == EXPECTED_VECTOR_DIMENSIONS) {
                "Preference vector dimension mismatch: expected=$EXPECTED_VECTOR_DIMENSIONS, actual=${currentPreference.size}"
            }
        }

        val alpha = getAlpha(actionType)

        // alpha가 0이면 취향 벡터에 영향 없음
        if (alpha == 0.0f) {
            return currentPreference ?: newProductVector.normalize()
        }

        // 신규 유저: 상품 벡터를 그대로 취향 벡터로 사용
        if (currentPreference == null) {
            return newProductVector.normalize()
        }

        // 기존 유저: EMA로 벡터 갱신
        val updated = FloatArray(currentPreference.size) { i ->
            currentPreference[i] * (1 - alpha) + newProductVector[i] * alpha
        }

        return updated.normalize()
    }

    /**
     * 여러 상품 벡터를 한 번에 반영하여 취향 벡터를 갱신합니다.
     * 배치 처리 시 사용합니다.
     *
     * @param currentPreference 현재 취향 벡터
     * @param productVectorsWithActions 상품 벡터와 행동 유형 쌍의 목록
     * @return 갱신된 취향 벡터
     */
    fun updateBatch(
        currentPreference: FloatArray?,
        productVectorsWithActions: List<Pair<FloatArray, String>>
    ): FloatArray? {
        if (productVectorsWithActions.isEmpty()) {
            return currentPreference
        }

        var result = currentPreference

        for ((productVector, actionType) in productVectorsWithActions) {
            result = update(result, productVector, actionType)
        }

        return result
    }

    /**
     * 행동 유형에 따른 EMA 가중치를 반환합니다.
     *
     * @param actionType 행동 유형
     * @return EMA 가중치 (0.0f ~ 0.5f), 알 수 없는 행동은 0.0f
     */
    private fun getAlpha(actionType: String): Float {
        return when (actionType.uppercase()) {
            "VIEW" -> ALPHA_VIEW
            "SEARCH" -> ALPHA_SEARCH      // 검색은 VIEW와 CLICK 사이
            "CLICK" -> ALPHA_CLICK
            "PURCHASE" -> ALPHA_PURCHASE
            "ADD_TO_CART" -> ALPHA_CLICK  // 장바구니 추가는 클릭과 동일
            "WISHLIST" -> ALPHA_VIEW      // 위시리스트는 조회와 동일
            else -> {
                log.warn { "Unknown actionType: $actionType, skipping preference update (alpha=0)" }
                0.0f
            }
        }
    }

    /**
     * 벡터를 정규화합니다 (단위 벡터로 변환).
     * Cosine similarity 사용을 위해 정규화가 필요합니다.
     */
    private fun FloatArray.normalize(): FloatArray {
        val norm = sqrt(this.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 0) {
            FloatArray(this.size) { i -> this[i] / norm }
        } else {
            this
        }
    }
}
