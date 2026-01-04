package com.rep.simulator.domain

import com.rep.event.user.ActionType
import com.rep.event.user.UserActionEvent
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * 가상 유저 세션을 관리하는 클래스
 *
 * 각 유저는 고유한 선호 카테고리를 가지며,
 * 70% 확률로 선호 카테고리의 상품에 대해 행동합니다.
 *
 * @param userId 유저 ID
 * @param productCountPerCategory 카테고리당 상품 수 (seed_products.py와 일치해야 함)
 */
class UserSession(
    val userId: String,
    private val productCountPerCategory: Int = 100
) {

    companion object {
        private val CATEGORIES = listOf(
            "ELECTRONICS",
            "FASHION",
            "FOOD",
            "BEAUTY",
            "SPORTS",
            "HOME",
            "BOOKS"
        )

        private val ACTION_WEIGHTS = mapOf(
            ActionType.VIEW to 45,        // 45% - 가장 빈번
            ActionType.CLICK to 25,       // 25% - 관심 표현
            ActionType.SEARCH to 10,      // 10% - 검색
            ActionType.ADD_TO_CART to 8,  // 8% - 장바구니
            ActionType.PURCHASE to 5,     // 5% - 구매 (가장 드묾)
            ActionType.WISHLIST to 7      // 7% - 위시리스트
        )

        private val TOTAL_WEIGHT = ACTION_WEIGHTS.values.sum()
    }

    // 유저별 선호 카테고리 (한 번 정해지면 유지)
    private val preferredCategory: String = CATEGORIES.random()

    // 유저별 선호 가격대 (10,000 ~ 500,000)
    private val preferredPriceRange: IntRange = run {
        val base = Random.nextInt(1, 50) * 10000
        base..(base + Random.nextInt(5, 20) * 10000)
    }

    // 최근 본 상품 ID 목록 (연속성 시뮬레이션)
    private val recentProducts = mutableListOf<String>()

    /**
     * 다음 행동 이벤트를 생성합니다.
     */
    fun nextAction(): UserActionEvent {
        val category = selectCategory()
        val productId = selectProduct(category)
        val actionType = selectActionType()

        // 상품 조회 시 최근 목록에 추가
        if (actionType == ActionType.VIEW || actionType == ActionType.CLICK) {
            recentProducts.add(productId)
            if (recentProducts.size > 20) {
                recentProducts.removeAt(0)
            }
        }

        return UserActionEvent.newBuilder()
            .setTraceId(UUID.randomUUID().toString())
            .setUserId(userId)
            .setProductId(productId)
            .setCategory(category)
            .setActionType(actionType)
            .setMetadata(buildMetadata(actionType))
            .setTimestamp(Instant.now())
            .build()
    }

    /**
     * 70% 확률로 선호 카테고리 선택
     */
    private fun selectCategory(): String {
        return if (Random.nextDouble() < 0.7) {
            preferredCategory
        } else {
            CATEGORIES.filter { it != preferredCategory }.random()
        }
    }

    /**
     * 상품 ID 선택
     * - 30% 확률로 최근 본 상품 재방문 (리마케팅 시뮬레이션)
     * - 70% 확률로 새 상품
     *
     * Note: seed_products.py에서 생성한 상품 ID 형식과 일치해야 함
     * 형식: PROD-{category[:3]}-{00001~productCountPerCategory}
     */
    private fun selectProduct(category: String): String {
        return if (recentProducts.isNotEmpty() && Random.nextDouble() < 0.3) {
            recentProducts.random()
        } else {
            val productNum = Random.nextInt(1, productCountPerCategory + 1)
            "PROD-${category.take(3)}-${productNum.toString().padStart(5, '0')}"
        }
    }

    /**
     * 가중치 기반 행동 타입 선택
     */
    private fun selectActionType(): ActionType {
        val randomValue = Random.nextInt(TOTAL_WEIGHT)
        var cumulative = 0

        for ((actionType, weight) in ACTION_WEIGHTS) {
            cumulative += weight
            if (randomValue < cumulative) {
                return actionType
            }
        }
        return ActionType.VIEW
    }

    /**
     * 행동 타입별 메타데이터 생성
     */
    private fun buildMetadata(actionType: ActionType): Map<String, String>? {
        return when (actionType) {
            ActionType.SEARCH -> mapOf(
                "searchQuery" to generateSearchQuery(),
                "resultCount" to Random.nextInt(10, 100).toString()
            )
            ActionType.VIEW -> mapOf(
                "referrer" to listOf("home", "search", "recommendation", "category").random(),
                "viewDurationMs" to Random.nextInt(1000, 30000).toString()
            )
            ActionType.CLICK -> mapOf(
                "position" to Random.nextInt(1, 20).toString()
            )
            ActionType.PURCHASE -> mapOf(
                "quantity" to Random.nextInt(1, 3).toString(),
                "price" to Random.nextInt(preferredPriceRange.first, preferredPriceRange.last).toString()
            )
            ActionType.ADD_TO_CART -> mapOf(
                "quantity" to Random.nextInt(1, 5).toString()
            )
            ActionType.WISHLIST -> mapOf(
                "source" to listOf("product_detail", "recommendation", "search_result").random()
            )
        }
    }

    /**
     * 검색어 생성 (실제 서비스를 시뮬레이션)
     */
    private fun generateSearchQuery(): String {
        val searchTerms = when (preferredCategory) {
            "ELECTRONICS" -> listOf("스마트폰", "노트북", "태블릿", "이어폰", "충전기", "갤럭시", "아이폰")
            "FASHION" -> listOf("운동화", "청바지", "티셔츠", "원피스", "자켓", "코트", "스니커즈")
            "FOOD" -> listOf("과자", "라면", "커피", "음료", "과일", "고기", "샐러드")
            "BEAUTY" -> listOf("로션", "선크림", "립스틱", "파운데이션", "마스크팩", "샴푸")
            "SPORTS" -> listOf("운동화", "요가매트", "덤벨", "러닝화", "스포츠웨어", "자전거")
            "HOME" -> listOf("쿠션", "이불", "조명", "수납함", "커튼", "러그")
            "BOOKS" -> listOf("소설", "자기계발", "경제", "역사", "과학", "에세이")
            else -> listOf("인기상품", "추천", "신상품", "할인")
        }
        return searchTerms.random()
    }
}
