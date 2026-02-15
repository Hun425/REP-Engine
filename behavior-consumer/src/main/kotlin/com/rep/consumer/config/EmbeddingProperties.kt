package com.rep.consumer.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Embedding Service 설정
 *
 * @see docs/adr-003-embedding-model.md
 */
@Validated
@ConfigurationProperties(prefix = "embedding.service")
data class EmbeddingProperties(
    @field:NotBlank(message = "url must not be blank")
    val url: String = "http://localhost:8000",

    @field:Positive(message = "timeoutMs must be positive")
    val timeoutMs: Long = 5000
)
