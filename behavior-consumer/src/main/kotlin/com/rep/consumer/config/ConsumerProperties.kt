package com.rep.consumer.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "consumer")
data class ConsumerProperties(
    @field:NotBlank(message = "topic must not be blank")
    val topic: String = "user.action.v1",

    @field:NotBlank(message = "dlqTopic must not be blank")
    val dlqTopic: String = "user.action.v1.dlq",

    @field:Positive(message = "bulkSize must be positive")
    val bulkSize: Int = 500,

    @field:Positive(message = "concurrency must be positive")
    val concurrency: Int = 3,

    @field:Positive(message = "maxRetries must be positive")
    val maxRetries: Int = 3,

    @field:Positive(message = "retryDelayMs must be positive")
    val retryDelayMs: Long = 1000,

    // DLQ 파일 백업 설정
    @field:Positive(message = "dlqFileMaxSizeBytes must be positive")
    val dlqFileMaxSizeBytes: Long = 10 * 1024 * 1024L,  // 10MB

    @field:NotBlank(message = "dlqLogsDir must not be blank")
    val dlqLogsDir: String = "logs",

    // 벡터 설정 (multilingual-e5-base)
    @field:Positive(message = "vectorDimensions must be positive")
    val vectorDimensions: Int = 768
)
