package com.rep.consumer.service

import com.rep.consumer.repository.ProductVectorRepository
import com.rep.consumer.repository.UserPreferenceRepository
import com.rep.event.user.UserActionEvent
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * 유저 취향 벡터 업데이터
 *
 * Kafka 이벤트를 받아서 유저의 취향 벡터를 실시간으로 갱신합니다.
 *
 * 처리 흐름:
 * 1. 이벤트에서 userId, productId, actionType 추출
 * 2. ES product_index에서 상품 벡터 조회
 * 3. Redis에서 현재 유저 취향 벡터 조회
 * 4. EMA로 취향 벡터 갱신
 * 5. Redis에 저장 (+ ES 백업)
 *
 * @see docs/phase%202.md
 * @see docs/adr-004-vector-storage.md
 */
@Component
class PreferenceUpdater(
    private val productVectorRepository: ProductVectorRepository,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val preferenceVectorCalculator: PreferenceVectorCalculator,
    private val meterRegistry: MeterRegistry
) {
    // 유저별 Mutex - Lost Update 방지
    // 같은 유저에 대한 동시 업데이트를 직렬화하여 Lost Update 방지
    private val userLocks = ConcurrentHashMap<String, Mutex>()

    private val updateSuccessCounter: Counter = Counter.builder("preference.update.success")
        .register(meterRegistry)

    private val updateSkippedCounter: Counter = Counter.builder("preference.update.skipped")
        .description("Skipped due to missing product vector")
        .register(meterRegistry)

    private val updateFailedCounter: Counter = Counter.builder("preference.update.failed")
        .register(meterRegistry)

    /**
     * 유저별 Mutex를 반환합니다.
     * ConcurrentHashMap.computeIfAbsent로 thread-safe하게 생성
     */
    private fun getUserLock(userId: String): Mutex =
        userLocks.computeIfAbsent(userId) { Mutex() }

    /**
     * 단일 이벤트에 대해 유저 취향 벡터를 갱신합니다.
     *
     * Lost Update 방지: 유저별 Mutex로 동시 업데이트 직렬화
     *
     * @param event 유저 행동 이벤트
     * @return 성공 여부
     */
    suspend fun updatePreference(event: UserActionEvent): Boolean {
        val userId = event.userId.toString()
        val productId = event.productId.toString()
        val actionType = event.actionType.toString()

        return try {
            // 1. 상품 벡터 조회 (락 밖에서 - 다른 유저에게 영향 없음)
            val productVector = productVectorRepository.getProductVector(productId)

            if (productVector == null) {
                log.debug { "Product vector not found for productId=$productId, skipping preference update" }
                updateSkippedCounter.increment()
                return true  // 상품 벡터가 없는 것은 오류가 아님
            }

            // 유저별 Mutex로 동시 업데이트 직렬화
            getUserLock(userId).withLock {
                // 2. 현재 유저 취향 벡터 조회
                val currentPreference = userPreferenceRepository.get(userId)
                val currentData = userPreferenceRepository.getWithMetadata(userId)
                val currentActionCount = currentData?.actionCount ?: 0

                // 3. EMA로 취향 벡터 갱신
                val updatedVector = preferenceVectorCalculator.update(
                    currentPreference = currentPreference,
                    newProductVector = productVector,
                    actionType = actionType
                )

                // 4. Redis에 저장 (+ ES 백업)
                userPreferenceRepository.save(
                    userId = userId,
                    vector = updatedVector,
                    actionCount = currentActionCount + 1
                )

                log.debug {
                    "Updated preference for userId=$userId, actionType=$actionType, " +
                        "actionCount=${currentActionCount + 1}"
                }
            }

            updateSuccessCounter.increment()
            true

        } catch (e: Exception) {
            log.error(e) { "Failed to update preference for userId=$userId" }
            updateFailedCounter.increment()
            false
        }
    }

    /**
     * 여러 이벤트에 대해 유저 취향 벡터를 배치로 갱신합니다.
     * 같은 유저의 이벤트는 순서대로 처리합니다.
     *
     * Lost Update 방지: 유저별 Mutex로 동시 업데이트 직렬화
     *
     * @param events 유저 행동 이벤트 목록
     * @return 성공한 이벤트 수
     */
    suspend fun updatePreferencesBatch(events: List<UserActionEvent>): Int {
        if (events.isEmpty()) return 0

        // 유저별로 이벤트 그룹화
        val eventsByUser = events.groupBy { it.userId.toString() }

        var successCount = 0

        for ((userId, userEvents) in eventsByUser) {
            try {
                // 해당 유저의 모든 이벤트에 필요한 상품 벡터를 한 번에 조회 (락 밖에서)
                val productIds = userEvents.map { it.productId.toString() }.distinct()
                val productVectors = productVectorRepository.getProductVectors(productIds)

                if (productVectors.isEmpty()) {
                    log.debug { "No product vectors found for userId=$userId, skipping all ${userEvents.size} events" }
                    updateSkippedCounter.increment(userEvents.size.toDouble())
                    continue
                }

                // 유저별 Mutex로 동시 업데이트 직렬화
                val userSuccessCount = getUserLock(userId).withLock {
                    var innerSuccessCount = 0

                    // 현재 취향 벡터 조회
                    var currentPreference = userPreferenceRepository.get(userId)
                    val currentData = userPreferenceRepository.getWithMetadata(userId)
                    var actionCount = currentData?.actionCount ?: 0

                    // 이벤트 순서대로 취향 벡터 갱신
                    for (event in userEvents) {
                        val productId = event.productId.toString()
                        val productVector = productVectors[productId]

                        if (productVector != null) {
                            currentPreference = preferenceVectorCalculator.update(
                                currentPreference = currentPreference,
                                newProductVector = productVector,
                                actionType = event.actionType.toString()
                            )
                            actionCount++
                            innerSuccessCount++
                            updateSuccessCounter.increment()
                        } else {
                            updateSkippedCounter.increment()
                        }
                    }

                    // 최종 결과 저장
                    if (currentPreference != null) {
                        userPreferenceRepository.save(userId, currentPreference, actionCount)
                    }

                    innerSuccessCount
                }

                successCount += userSuccessCount

            } catch (e: Exception) {
                log.error(e) { "Failed to batch update preferences for userId=$userId" }
                updateFailedCounter.increment(userEvents.size.toDouble())
            }
        }

        if (successCount > 0) {
            log.info { "Batch updated $successCount/${events.size} preference vectors" }
        }

        return successCount
    }
}
