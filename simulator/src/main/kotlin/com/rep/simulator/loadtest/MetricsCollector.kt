package com.rep.simulator.loadtest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

private val log = KotlinLogging.logger {}

/**
 * Prometheus HTTP API를 통해 실시간 메트릭을 수집합니다.
 */
@Component
class MetricsCollector(
    private val properties: LoadTestProperties
) {
    private val restTemplate = RestTemplate()

    suspend fun collect(): LoadTestMetrics = withContext(Dispatchers.IO) {
        LoadTestMetrics(
            kafkaConsumerLag = query("sum(kafka_consumergroup_lag)"),
            kafkaProcessedRate = query("sum(rate(kafka_consumer_processed_total[30s]))"),
            esBulkSuccessRate = query("sum(rate(es_bulk_success_total[30s]))"),
            esBulkFailedTotal = query("sum(es_bulk_failed_total)"),
            preferenceUpdateRate = query("sum(rate(preference_update_success_total[30s]))"),
            recApiP50Ms = query(
                """histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{application="recommendation-api"}[30s])) by (le)) * 1000"""
            ),
            recApiP95Ms = query(
                """histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="recommendation-api"}[30s])) by (le)) * 1000"""
            ),
            recApiP99Ms = query(
                """histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application="recommendation-api"}[30s])) by (le)) * 1000"""
            ),
            notificationTriggered = query("sum(notification_triggered_total)"),
            notificationRateLimited = query("sum(notification_rate_limited_total)"),
            jvmHeapUsedBytes = query("""sum(jvm_memory_used_bytes{area="heap"})"""),
            redisMemoryUsedBytes = query("redis_memory_used_bytes")
        )
    }

    private fun query(promql: String): Double? {
        return try {
            val url = "${properties.prometheusUrl}/api/v1/query?query={query}"
            val response = restTemplate.getForObject(url, PrometheusResponse::class.java, promql)
            val value = response?.data?.result?.firstOrNull()?.value?.getOrNull(1) as? String
            value?.toDoubleOrNull()
        } catch (e: Exception) {
            log.debug { "Prometheus query failed for [$promql]: ${e.message}" }
            null
        }
    }
}

// Prometheus API response models
data class PrometheusResponse(
    val status: String? = null,
    val data: PrometheusData? = null
)

data class PrometheusData(
    val resultType: String? = null,
    val result: List<PrometheusResult>? = null
)

data class PrometheusResult(
    val metric: Map<String, String>? = null,
    val value: List<Any>? = null
)
