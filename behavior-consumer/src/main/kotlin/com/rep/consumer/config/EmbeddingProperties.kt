package com.rep.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Embedding Service 설정
 *
 * @see docs/adr-003-embedding-model.md
 */
@ConfigurationProperties(prefix = "embedding.service")
data class EmbeddingProperties(
    val url: String = "http://localhost:8000",
    val timeoutMs: Long = 5000,
    val batchSize: Int = 32
)
