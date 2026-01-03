package com.rep.recommendation.repository

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.json.JsonData
import mu.KotlinLogging
import org.springframework.stereotype.Repository

private val log = KotlinLogging.logger {}

/**
 * 유저 행동 로그 Repository
 *
 * ES user_behavior_index에서 유저의 최근 행동을 조회합니다.
 * 주로 추천에서 이미 본 상품을 제외하기 위해 사용됩니다.
 */
@Repository
class UserBehaviorRepository(
    private val esClient: ElasticsearchClient
) {
    companion object {
        private const val INDEX_NAME = "user_behavior_index"
    }

    /**
     * 유저가 최근에 본 상품 ID 목록을 조회합니다.
     *
     * @param userId 유저 ID
     * @param limit 조회할 최대 개수
     * @return 상품 ID 목록 (최신순)
     */
    fun getRecentViewedProducts(userId: String, limit: Int = 100): List<String> {
        return try {
            val response = esClient.search({ s ->
                s.index(INDEX_NAME)
                    .size(limit)
                    .query { q ->
                        q.bool { b ->
                            b.must { m ->
                                m.term { t -> t.field("userId").value(userId) }
                            }
                            // 최근 7일 이내
                            b.must { m ->
                                m.range { r -> r.field("timestamp").gte(JsonData.of("now-7d")) }
                            }
                            b
                        }
                    }
                    .sort { sort -> sort.field { f -> f.field("timestamp").order(SortOrder.Desc) } }
                    .source { src -> src.filter { f -> f.includes("productId") } }
            }, Map::class.java)

            response.hits().hits()
                .mapNotNull { hit ->
                    @Suppress("UNCHECKED_CAST")
                    (hit.source() as? Map<String, Any>)?.get("productId")?.toString()
                }
                .distinct()

        } catch (e: Exception) {
            log.error(e) { "Failed to get recent viewed products for userId=$userId" }
            emptyList()
        }
    }

    /**
     * 유저가 최근에 구매한 상품 ID 목록을 조회합니다.
     *
     * @param userId 유저 ID
     * @param limit 조회할 최대 개수
     * @return 상품 ID 목록 (최신순)
     */
    fun getRecentPurchasedProducts(userId: String, limit: Int = 50): List<String> {
        return try {
            val response = esClient.search({ s ->
                s.index(INDEX_NAME)
                    .size(limit)
                    .query { q ->
                        q.bool { b ->
                            b.must { m ->
                                m.term { t -> t.field("userId").value(userId) }
                            }
                            b.must { m ->
                                m.term { t -> t.field("actionType").value("PURCHASE") }
                            }
                            // 최근 30일 이내
                            b.must { m ->
                                m.range { r -> r.field("timestamp").gte(JsonData.of("now-30d")) }
                            }
                            b
                        }
                    }
                    .sort { sort -> sort.field { f -> f.field("timestamp").order(SortOrder.Desc) } }
                    .source { src -> src.filter { f -> f.includes("productId") } }
            }, Map::class.java)

            response.hits().hits()
                .mapNotNull { hit ->
                    @Suppress("UNCHECKED_CAST")
                    (hit.source() as? Map<String, Any>)?.get("productId")?.toString()
                }
                .distinct()

        } catch (e: Exception) {
            log.error(e) { "Failed to get recent purchased products for userId=$userId" }
            emptyList()
        }
    }
}
