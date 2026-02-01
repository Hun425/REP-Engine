package com.rep.notification.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 알림 서비스 설정
 *
 * @see docs/phase%204.md
 */
@Validated
@ConfigurationProperties(prefix = "notification")
data class NotificationProperties(
    /** 가격 하락 알림 임계값 (%) - 이 비율 이상 하락 시 알림 */
    @field:Positive(message = "priceDropThreshold must be positive")
    val priceDropThreshold: Int = 10,

    /** 관심 유저 조회 시 최대 대상 수 */
    @field:Positive(message = "targetUserLimit must be positive")
    val targetUserLimit: Int = 10000,

    /** 관심 유저 조회 기간 (일) - VIEW, CLICK, ADD_TO_CART 대상 */
    @field:Positive(message = "interestedUserDays must be positive")
    val interestedUserDays: Int = 30,

    /** 장바구니 유저 조회 기간 (일) - 재입고 알림 대상 */
    @field:Positive(message = "cartUserDays must be positive")
    val cartUserDays: Int = 7,

    /** 유저별 일일 알림 최대 횟수 */
    @field:Positive(message = "dailyLimitPerUser must be positive")
    val dailyLimitPerUser: Int = 10,

    /** 동일 알림 중복 방지 기간 (시간) */
    @field:Positive(message = "duplicatePreventionHours must be positive")
    val duplicatePreventionHours: Long = 1,

    /** Kafka 토픽 설정 */
    @field:NotBlank(message = "inventoryTopic must not be blank")
    val inventoryTopic: String = "product.inventory.v1",

    @field:NotBlank(message = "notificationTopic must not be blank")
    val notificationTopic: String = "notification.push.v1",

    /** DLQ 설정 */
    @field:Valid
    val dlq: DlqConfig = DlqConfig(),

    /** 배치 처리 설정 (Kafka 버스트 방지) */
    @field:Positive(message = "batchSize must be positive")
    val batchSize: Int = 100,

    @field:Positive(message = "batchDelayMs must be positive")
    val batchDelayMs: Long = 50,

    /** 추천 알림 설정 (일일 배치) */
    @field:Valid
    val recommendation: RecommendationConfig = RecommendationConfig()
) {
    /**
     * DLQ (Dead Letter Queue) 설정
     *
     * 처리 실패한 메시지를 별도 토픽으로 이동하여 추후 분석/재처리
     */
    data class DlqConfig(
        /** DLQ 활성화 여부 */
        val enabled: Boolean = true,

        /** DLQ 토픽 접미사 (원본 토픽 + 접미사) */
        val topicSuffix: String = ".dlq",

        /** 재시도 횟수 (재시도 후에도 실패 시 DLQ로 이동) */
        @field:Positive(message = "maxRetries must be positive")
        val maxRetries: Int = 3,

        /** 재시도 간격 (ms) */
        @field:Positive(message = "retryBackoffMs must be positive")
        val retryBackoffMs: Long = 1000
    )

    /**
     * 추천 알림 배치 설정
     *
     * ShedLock + @Scheduled로 매일 지정 시간에 실행
     * - 다중 인스턴스 환경에서 Redis 분산 락으로 단일 실행 보장
     */
    data class RecommendationConfig(
        /** 추천 알림 활성화 여부 */
        val enabled: Boolean = true,

        /** 실행 주기 (cron 표현식, KST 기준) */
        val cron: String = "0 0 9 * * *",

        /** 활성 유저 조회 기간 (일) - 이 기간 내 활동한 유저 대상 */
        @field:Positive(message = "activeUserDays must be positive")
        val activeUserDays: Int = 7,

        /** 추천 상품 개수 (알림당) */
        @field:Positive(message = "limit must be positive")
        val limit: Int = 3,

        /** recommendation-api URL */
        @field:NotBlank(message = "apiUrl must not be blank")
        val apiUrl: String = "http://recommendation-api:8082"
    )
}
