package com.rep.simulator.loadtest

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "load-test")
data class LoadTestProperties(
    val prometheusUrl: String = "http://localhost:9090",
    val recommendationApiUrl: String = "http://localhost:8080",
    val resultsDir: String = "./load-test-results",
    val metricsCollectIntervalMs: Long = 3000
)
