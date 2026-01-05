package com.rep.notification.service

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.json.JsonData
import com.rep.notification.config.NotificationProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * 알림 대상 유저 추출 서비스
 *
 * ES user_behavior_index에서 특정 상품에 관심을 보인 유저를 집계합니다.
 *
 * @see docs/phase%204.md - Target Resolver
 */
@Component
class TargetResolver(
    private val esClient: ElasticsearchClient,
    private val properties: NotificationProperties,
    meterRegistry: MeterRegistry
) {
    companion object {
        private const val BEHAVIOR_INDEX = "user_behavior_index"
    }

    private val queryCounter = Counter.builder("notification.target.query")
        .description("Target user queries executed")
        .register(meterRegistry)

    private val targetFoundCounter = Counter.builder("notification.target.found")
        .description("Target users found")
        .register(meterRegistry)

    /**
     * 특정 상품에 관심을 보인 유저 목록을 추출합니다.
     *
     * @param productId 상품 ID
     * @param actionTypes 관심 행동 유형 (VIEW, CLICK, ADD_TO_CART 등)
     * @param withinDays 조회 기간 (일)
     * @return 유저 ID 목록
     */
    fun findInterestedUsers(
        productId: String,
        actionTypes: List<String>,
        withinDays: Int = properties.interestedUserDays
    ): List<String> {
        return try {
            queryCounter.increment()

            val response = esClient.search({ s ->
                s.index(BEHAVIOR_INDEX)
                    .size(0)  // 집계만 필요
                    .query { q ->
                        q.bool { b ->
                            // 상품 ID 필터
                            b.must { m ->
                                m.term { t -> t.field("productId").value(productId) }
                            }
                            // 행동 유형 필터
                            b.must { m ->
                                m.terms { t ->
                                    t.field("actionType").terms { tv ->
                                        tv.value(actionTypes.map { FieldValue.of(it) })
                                    }
                                }
                            }
                            // 기간 필터
                            b.must { m ->
                                m.range { r ->
                                    r.field("timestamp").gte(JsonData.of("now-${withinDays}d"))
                                }
                            }
                            b
                        }
                    }
                    .aggregations("users") { agg ->
                        agg.terms { t ->
                            t.field("userId").size(properties.targetUserLimit)
                        }
                    }
            }, Void::class.java)

            val users = response.aggregations()["users"]
                ?.sterms()?.buckets()?.array()
                ?.map { it.key().stringValue() } ?: emptyList()

            targetFoundCounter.increment(users.size.toDouble())

            log.info {
                "Found ${users.size} interested users for productId=$productId, " +
                    "actionTypes=$actionTypes, withinDays=$withinDays"
            }

            users

        } catch (e: Exception) {
            log.error(e) { "Failed to find interested users for productId=$productId" }
            emptyList()
        }
    }

    /**
     * 특정 상품을 장바구니에 담은 유저 목록을 추출합니다.
     * 재입고 알림 대상 유저 조회에 사용됩니다.
     *
     * @param productId 상품 ID
     * @return 유저 ID 목록
     */
    fun findUsersWithCartItem(productId: String): List<String> {
        return findInterestedUsers(
            productId = productId,
            actionTypes = listOf("ADD_TO_CART"),
            withinDays = properties.cartUserDays
        )
    }
}
