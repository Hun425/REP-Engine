package com.rep.notification.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 스케줄링 활성화 설정
 *
 * 추천 알림 배치 작업을 위한 @Scheduled 지원
 *
 * @see RecommendationScheduler
 */
@Configuration
@EnableScheduling
class SchedulingConfig
