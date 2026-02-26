# 가독성 리팩토링 비교 노트 (Kotlin + Spring)

## 목적

이 문서는 실제 코드 변경을 기준으로,  
`가독성은 높이고 응집도는 유지`하는 리팩토링 방법을 학습용으로 정리한 자료입니다.

대상 파일:
1. `notification-service/.../EventDetector.kt`
2. `simulator/.../LoadTestService.kt`

---

## 사례 1: EventDetector

파일: `/Users/hun/Desktop/etc/REP-engine/notification-service/src/main/kotlin/com/rep/notification/service/EventDetector.kt`

### 문제(기존 코드)
1. `detectPriceDrop`/`detectRestock` 메서드 내부가 너무 길고 중복이 많음
2. 비즈니스 판단, 배치 발송, 관측(Observation), 알림 객체 생성이 한 메서드에 혼합됨
3. 같은 패턴(rate limit + chunk + delay)이 두 메서드에 반복됨

### 변경 포인트 요약
1. 입력 해석: `extractPriceDrop`, `extractRestock` 분리
2. 관측 래퍼: `withObservation` 분리
3. 배치 발송 공통화: `sendNotificationsInBatches`
4. 알림 생성 분리: `buildPriceDropNotification`, `buildRestockNotification`

### Before vs After

#### 1) 메인 흐름 단순화

Before:
```kotlin
suspend fun detectPriceDrop(event: ProductInventoryEvent) {
    val previousPrice = event.previousPrice ?: return
    val currentPrice = event.currentPrice ?: return
    if (previousPrice <= 0 || currentPrice < 0) return

    val dropPercentage = ((previousPrice - currentPrice) / previousPrice * 100).toInt()
    if (dropPercentage >= properties.priceDropThreshold) {
        // 관측 시작 + 대상 조회 + 배치 루프 + 알림 생성 + 발송 + 로그
    }
}
```

After:
```kotlin
suspend fun detectPriceDrop(event: ProductInventoryEvent) {
    val priceChange = extractPriceDrop(event) ?: return
    if (priceChange.dropPercentage < properties.priceDropThreshold) return

    withObservation(...) {
        val targetUsers = targetResolver.findInterestedUsers(...)
        if (targetUsers.isEmpty()) return@withObservation

        val productName = resolveProductName(priceChange.productId)
        val sentCount = sendNotificationsInBatches(...) { userId ->
            buildPriceDropNotification(...)
        }
        log.info { "..., sent=${sentCount.sentCount}, batches=${sentCount.batchCount}" }
    }
}
```

핵심 효과:
1. 최상위 메서드가 “비즈니스 스토리”만 보여줌
2. 세부 구현은 private 함수로 내려가서 읽기 부담 감소

#### 2) 중복 루프 제거

Before:
```kotlin
for ((batchIndex, batch) in batches.withIndex()) {
    for (userId in batch) {
        if (!rateLimiter.canSend(...)) continue
        notificationProducer.send(notification)
    }
    if (batchIndex < batches.size - 1) delay(...)
}
```

After:
```kotlin
private suspend fun sendNotificationsInBatches(...): BatchSendResult {
    val batches = targetUsers.chunked(properties.batchSize)
    var sentCount = 0
    for ((batchIndex, batch) in batches.withIndex()) {
        ...
    }
    return BatchSendResult(sentCount = sentCount, batchCount = batches.size)
}
```

핵심 효과:
1. 중복 제거로 변경 포인트 단일화
2. 배치 처리 정책이 한 곳에 모여 응집도 유지

---

## 사례 2: LoadTestService

파일: `/Users/hun/Desktop/etc/REP-engine/simulator/src/main/kotlin/com/rep/simulator/loadtest/LoadTestService.kt`

### 문제(기존 코드)
1. `startTest()`에 상태 초기화 + 메트릭 루프 + 시나리오 실행 로직이 몰림
2. `stopTest()`도 중지/취소/정리 절차가 인라인으로 길게 배치됨
3. 시작/중지 오케스트레이션의 의도가 코드 구조에서 바로 보이지 않음

### 변경 포인트 요약
1. 시작 절차를 단계 메서드로 분리
2. 메트릭 수집 루프를 `launchMetricsCollection` + `collectAndStoreMetrics`로 분리
3. 시나리오 실행을 `launchScenarioOrchestrator` + `runScenario`로 분리
4. 중지 절차를 `stopGenerators` + `cancelRunningJobs`로 분리

### Before vs After

#### 1) startTest 구조

Before:
```kotlin
fun startTest(request: LoadTestStartRequest): LoadTestStatus {
    lock.withLock {
        // 실행 중 체크
        // 상태 초기화
        // metricsJob launch
        // orchestratorJob launch
        return getStatus()
    }
}
```

After:
```kotlin
fun startTest(request: LoadTestStartRequest): LoadTestStatus {
    lock.withLock {
        ensureNoRunningTest()
        val testId = initializeTestState(request)
        metricsJob = launchMetricsCollection()
        orchestratorJob = launchScenarioOrchestrator(testId, request)
        return getStatus()
    }
}
```

핵심 효과:
1. 읽는 순서가 실행 순서와 동일
2. 각 단계 책임이 분리되어 디버깅/수정이 쉬움

#### 2) stopTest 구조

Before:
```kotlin
fun stopTest(): LoadTestStatus {
    lock.withLock {
        trafficSimulator.stopSimulation()
        inventorySimulator.stopSimulation()
        recLoadGenerator.stop()
        orchestratorJob?.cancel()
        metricsJob?.cancel()
        saveResult()
        ...
    }
}
```

After:
```kotlin
fun stopTest(): LoadTestStatus {
    lock.withLock {
        stopGenerators()
        cancelRunningJobs()
        saveResult()
        ...
    }
}
```

핵심 효과:
1. 중지 절차의 의도가 메서드 이름으로 드러남
2. 중지 정책 변경 시 수정 위치가 명확함

---

## 이번 리팩토링이 응집도를 해치지 않은 이유

1. 공통화한 함수들은 모두 같은 클래스 내부 `private`로 유지
2. 기능을 다른 계층/패키지로 빼지 않고, 해당 클래스의 책임 범위 안에서만 분해
3. “재사용을 위한 과도한 추상화”가 아니라 “읽기 위한 의도 분리” 중심으로 적용

---

## 실무에서 그대로 쓰는 체크리스트

리팩토링 전:
1. 이 메서드가 두 가지 이상 일을 하고 있는가?
2. 같은 루프/조건/로그 패턴이 반복되는가?
3. top-level 흐름을 30초 안에 설명 가능한가?

리팩토링 후:
1. top-level 메서드가 비즈니스 문장처럼 읽히는가?
2. 세부 구현은 private 함수로 내려갔는가?
3. 변경 포인트가 줄었는가(중복 제거)?

---

## 참고

원본 스타일 가이드:
1. `/Users/hun/Desktop/etc/REP-engine/docs/code-style-guide.md`
2. `/Users/hun/Desktop/etc/REP-engine/docs/code-style-guide.ko.md`
