package com.rep.notification.config

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
    val notificationTopic: String = "notification.push.v1"
)
