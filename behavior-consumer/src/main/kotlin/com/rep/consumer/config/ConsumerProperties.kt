package com.rep.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "consumer")
data class ConsumerProperties(
    val topic: String = "user.action.v1",
    val dlqTopic: String = "user.action.v1.dlq",
    val bulkSize: Int = 500,
    val concurrency: Int = 3,
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 1000,
    // DLQ 파일 백업 설정
    val dlqFileMaxSizeBytes: Long = 10 * 1024 * 1024L,  // 10MB
    val dlqLogsDir: String = "logs",
    // 벡터 설정 (multilingual-e5-base)
    val vectorDimensions: Int = 384
)
