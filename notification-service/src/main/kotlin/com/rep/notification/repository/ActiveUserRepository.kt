package com.rep.notification.repository

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.JsonData
import com.rep.notification.config.NotificationProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.stereotype.Repository

private val log = KotlinLogging.logger {}

/**
 * 활성 유저 조회 Repository
 *
 * ES user_behavior_index에서 최근 활동한 유저를 집계합니다.
 * 추천 알림 배치 작업에서 대상 유저 추출에 사용됩니다.
 *
 * @see RecommendationScheduler
 */
@Repository
class ActiveUserRepository(
    private val esClient: ElasticsearchClient,
    private val properties: NotificationProperties,
    meterRegistry: MeterRegistry
) {
    companion object {
        private const val BEHAVIOR_INDEX = "user_behavior_index"
    }

    private val queryCounter = Counter.builder("notification.active_user.query")
        .description("Active user queries executed")
        .register(meterRegistry)

    private val usersFoundCounter = Counter.builder("notification.active_user.found")
        .description("Active users found")
        .register(meterRegistry)

    /**
     * 최근 활동한 유저 목록을 조회합니다.
     *
     * @param withinDays 조회 기간 (일) - 이 기간 내 활동한 유저 추출
     * @return 유저 ID 목록
     */
    fun getActiveUsers(withinDays: Int = properties.recommendation.activeUserDays): List<String> {
        return try {
            queryCounter.increment()

            val response = esClient.search({ s ->
                s.index(BEHAVIOR_INDEX)
                    .size(0)  // 집계만 필요
                    .query { q ->
                        q.range { r ->
                            r.field("timestamp").gte(JsonData.of("now-${withinDays}d"))
                        }
                    }
                    .aggregations("active_users") { agg ->
                        agg.terms { t ->
                            t.field("userId").size(properties.targetUserLimit)
                        }
                    }
            }, Void::class.java)

            val users = response.aggregations()["active_users"]
                ?.sterms()?.buckets()?.array()
                ?.map { it.key().stringValue() } ?: emptyList()

            usersFoundCounter.increment(users.size.toDouble())

            log.info {
                "Found ${users.size} active users within last $withinDays days"
            }

            users

        } catch (e: Exception) {
            log.error(e) { "Failed to get active users" }
            emptyList()
        }
    }
}
