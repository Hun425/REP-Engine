package com.rep.simulator.tracing

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch.core.IndexRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.json.JsonData
import com.rep.simulator.tracing.model.AnomalyType
import com.rep.simulator.tracing.model.Severity
import com.rep.simulator.tracing.model.TraceAnomaly
import com.rep.simulator.tracing.model.TraceBookmark
import mu.KotlinLogging
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

private val log = KotlinLogging.logger {}

@Repository
class AnomalyRepository(
    private val esClient: ElasticsearchClient
) {
    companion object {
        private const val INDEX = "trace_anomaly_index"
    }

    fun save(anomaly: TraceAnomaly): String? {
        val id = anomaly.id ?: UUID.randomUUID().toString()
        val doc = mapOf(
            "traceId" to anomaly.traceId,
            "type" to anomaly.type.name,
            "severity" to anomaly.severity.name,
            "serviceName" to anomaly.serviceName,
            "operationName" to anomaly.operationName,
            "durationMs" to anomaly.durationMs,
            "thresholdMs" to anomaly.thresholdMs,
            "errorMessage" to anomaly.errorMessage,
            "spanCount" to anomaly.spanCount,
            "metadata" to anomaly.metadata,
            "note" to anomaly.note,
            "isBookmark" to anomaly.isBookmark,
            "detectedAt" to anomaly.detectedAt.toEpochMilli(),
            "createdAt" to anomaly.createdAt.toEpochMilli()
        )

        return try {
            esClient.index(
                IndexRequest.of { r ->
                    r.index(INDEX).id(id).document(doc)
                }
            )
            id
        } catch (e: Exception) {
            log.error(e) { "Failed to save anomaly: traceId=${anomaly.traceId}, type=${anomaly.type}" }
            null
        }
    }

    fun searchAnomalies(
        type: AnomalyType?,
        service: String?,
        from: Long?,
        to: Long?,
        page: Int,
        size: Int
    ): List<TraceAnomaly> {
        return try {
            val response = esClient.search({ s ->
                s.index(INDEX)
                    .from(page * size)
                    .size(size)
                    .sort { sort -> sort.field { f -> f.field("detectedAt").order(SortOrder.Desc) } }
                    .query { q ->
                        q.bool { b ->
                            // isBookmark = false (anomalies only)
                            b.must { m -> m.term { t -> t.field("isBookmark").value(false) } }

                            if (type != null) {
                                b.must { m -> m.term { t -> t.field("type").value(type.name) } }
                            }
                            if (service != null) {
                                b.must { m -> m.term { t -> t.field("serviceName").value(service) } }
                            }
                            if (from != null || to != null) {
                                b.must { m ->
                                    m.range { r ->
                                        val rangeBuilder = r.field("detectedAt")
                                        if (from != null) rangeBuilder.gte(JsonData.of(from))
                                        if (to != null) rangeBuilder.lte(JsonData.of(to))
                                        rangeBuilder
                                    }
                                }
                            }
                            b
                        }
                    }
            }, Map::class.java)

            response.hits().hits().mapNotNull { hit ->
                @Suppress("UNCHECKED_CAST")
                val source = hit.source() as? Map<String, Any?> ?: return@mapNotNull null
                mapToAnomaly(hit.id() ?: return@mapNotNull null, source)
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to search anomalies" }
            emptyList()
        }
    }

    fun getBookmarks(): List<TraceBookmark> {
        return try {
            val response = esClient.search({ s ->
                s.index(INDEX)
                    .size(100)
                    .sort { sort -> sort.field { f -> f.field("createdAt").order(SortOrder.Desc) } }
                    .query { q ->
                        q.term { t -> t.field("isBookmark").value(true) }
                    }
            }, Map::class.java)

            response.hits().hits().mapNotNull { hit ->
                @Suppress("UNCHECKED_CAST")
                val source = hit.source() as? Map<String, Any?> ?: return@mapNotNull null
                TraceBookmark(
                    id = hit.id(),
                    traceId = source["traceId"]?.toString() ?: "",
                    serviceName = source["serviceName"]?.toString() ?: "",
                    operationName = source["operationName"]?.toString() ?: "",
                    durationMs = (source["durationMs"] as? Number)?.toLong() ?: 0,
                    note = source["note"]?.toString(),
                    createdAt = Instant.ofEpochMilli((source["createdAt"] as? Number)?.toLong() ?: 0)
                )
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to get bookmarks" }
            emptyList()
        }
    }

    fun saveBookmark(bookmark: TraceBookmark): String {
        val id = UUID.randomUUID().toString()
        val doc = mapOf(
            "traceId" to bookmark.traceId,
            "type" to "BOOKMARK",
            "severity" to "WARNING",
            "serviceName" to bookmark.serviceName,
            "operationName" to bookmark.operationName,
            "durationMs" to bookmark.durationMs,
            "note" to bookmark.note,
            "isBookmark" to true,
            "detectedAt" to bookmark.createdAt.toEpochMilli(),
            "createdAt" to bookmark.createdAt.toEpochMilli()
        )

        try {
            esClient.index(IndexRequest.of { r -> r.index(INDEX).id(id).document(doc) })
        } catch (e: Exception) {
            log.error(e) { "Failed to save bookmark: traceId=${bookmark.traceId}" }
            throw e
        }
        return id
    }

    fun deleteBookmark(id: String) {
        try {
            esClient.delete { d -> d.index(INDEX).id(id) }
        } catch (e: Exception) {
            log.error(e) { "Failed to delete bookmark: id=$id" }
            throw e
        }
    }

    fun updateBookmarkNote(id: String, note: String) {
        try {
            esClient.update<Map<*, *>, Map<String, Any?>>({ u ->
                u.index(INDEX).id(id).doc(mapOf("note" to note))
            }, Map::class.java)
        } catch (e: Exception) {
            log.error(e) { "Failed to update bookmark note: id=$id" }
            throw e
        }
    }

    fun existsByTraceIdAndType(traceId: String, type: AnomalyType): Boolean {
        return try {
            val response = esClient.count { c ->
                c.index(INDEX).query { q ->
                    q.bool { b ->
                        b.must { m -> m.term { t -> t.field("traceId").value(traceId) } }
                        b.must { m -> m.term { t -> t.field("type").value(type.name) } }
                        b.must { m -> m.term { t -> t.field("isBookmark").value(false) } }
                        b
                    }
                }
            }
            response.count() > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun mapToAnomaly(id: String, source: Map<String, Any?>): TraceAnomaly {
        return TraceAnomaly(
            id = id,
            traceId = source["traceId"]?.toString() ?: "",
            type = try {
                AnomalyType.valueOf(source["type"]?.toString() ?: "SLOW_TRACE")
            } catch (e: Exception) {
                log.warn { "Unknown anomaly type: ${source["type"]}, defaulting to SLOW_TRACE" }
                AnomalyType.SLOW_TRACE
            },
            severity = try {
                Severity.valueOf(source["severity"]?.toString() ?: "WARNING")
            } catch (e: Exception) {
                log.warn { "Unknown severity: ${source["severity"]}, defaulting to WARNING" }
                Severity.WARNING
            },
            serviceName = source["serviceName"]?.toString() ?: "",
            operationName = source["operationName"]?.toString() ?: "",
            durationMs = (source["durationMs"] as? Number)?.toLong() ?: 0,
            thresholdMs = (source["thresholdMs"] as? Number)?.toLong(),
            errorMessage = source["errorMessage"]?.toString(),
            spanCount = (source["spanCount"] as? Number)?.toInt(),
            note = source["note"]?.toString(),
            isBookmark = source["isBookmark"] as? Boolean ?: false,
            detectedAt = Instant.ofEpochMilli((source["detectedAt"] as? Number)?.toLong() ?: 0),
            createdAt = Instant.ofEpochMilli((source["createdAt"] as? Number)?.toLong() ?: 0)
        )
    }
}
