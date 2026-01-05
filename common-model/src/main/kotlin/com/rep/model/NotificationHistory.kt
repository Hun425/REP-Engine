package com.rep.model

/**
 * ES notification_history_index 문서 구조
 *
 * 알림 발송 이력을 저장합니다.
 *
 * @see docs/phase%204.md - 알림 이력 저장
 */
data class NotificationHistory(
    val notificationId: String? = null,
    val userId: String? = null,
    val productId: String? = null,
    val type: String? = null,
    val title: String? = null,
    val channels: List<String>? = null,
    val status: String? = null,
    val sentAt: Long? = null
)

/**
 * 알림 발송 상태
 */
enum class SendStatus {
    /** 발송 완료 */
    SENT,
    /** 발송 실패 */
    FAILED,
    /** Rate Limit으로 인한 차단 */
    RATE_LIMITED,
    /** 유저가 알림 수신 거부 */
    USER_OPTED_OUT
}
