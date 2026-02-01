# GitHub Issues 생성 목록

PR #3 문서 동기화 작업 중 발견된 코드 개선점입니다.
아래 내용으로 GitHub Issues를 수동으로 생성해주세요.

---

## Issue #1: refactor(behavior-consumer): 코드 품질 개선

### 개요
behavior-consumer 모듈의 코드 품질 개선 작업입니다.

### 개선 항목

#### 1. BulkIndexer.indexBatchSync() repeat 루프 개선
**파일**: `behavior-consumer/src/main/kotlin/com/rep/consumer/service/BulkIndexer.kt`

현재 `repeat(3)` 구문을 사용하고 있는데, `while` 루프로 변경하면 가독성이 더 좋습니다.

```kotlin
// 현재
repeat(3) { attempt ->
    try {
        // ...
        return handleBulkResponse(response, events)
    } catch (e: Exception) {
        delay(1000L * (1L shl attempt))
    }
}

// 제안
var attempt = 0
while (attempt < 3) {
    try {
        return handleBulkResponse(response, events)
    } catch (e: Exception) {
        attempt++
        if (attempt < 3) delay(1000L * (1L shl attempt))
    }
}
```

#### 2. UserPreferenceRepository SupervisorJob + try-catch 중복 정리
**파일**: `behavior-consumer/src/main/kotlin/com/rep/consumer/repository/UserPreferenceRepository.kt`

ES 백업 시 SupervisorJob + try-catch가 중복되어 있습니다.
SupervisorJob의 목적이 자식 코루틴 실패를 격리하는 것이므로, 내부 try-catch와 역할이 겹칩니다.

#### 3. DlqProducer 파일 로테이션 정책 명확화
**파일**: `behavior-consumer/src/main/kotlin/com/rep/consumer/service/DlqProducer.kt`

파일 로테이션 시 백업 파일이 무한히 생성될 수 있습니다.
백업 파일 보존 개수 제한 또는 삭제 정책 추가 필요.

### 우선순위
- **Low** - 기능 동작에 문제 없음

---

## Issue #2: refactor(notification-service): 코드 품질 개선

### 개요
notification-service 모듈의 코드 품질 개선 작업입니다.

### 개선 항목

#### 1. InventoryEventConsumer의 runBlocking 패턴 주석 보강
**파일**: `notification-service/src/main/kotlin/com/rep/notification/consumer/InventoryEventConsumer.kt`

Kafka Consumer에서 `runBlocking`을 사용하는 이유에 대한 주석을 보강하면 좋겠습니다.
(suspend 함수를 일반 함수에서 호출하기 위함, Virtual Thread 덕분에 블로킹 안전)

#### 2. RecommendationScheduler의 canSend() 호출 패턴 검토
**파일**: `notification-service/src/main/kotlin/com/rep/notification/scheduler/RecommendationScheduler.kt`

배치 루프 내에서 매번 `rateLimiter.canSend()`를 호출합니다.
Redis 호출 횟수가 많아지므로, 배치 최적화 검토 필요.

#### 3. TargetResolver의 에러 처리 개선
**파일**: `notification-service/src/main/kotlin/com/rep/notification/service/TargetResolver.kt`

빈 결과 vs 에러 구분이 명확하지 않습니다.
- 빈 결과: `emptyList()` 반환
- 에러: 예외 발생 또는 `null` 반환

Result 타입 또는 sealed class로 구분하면 호출자가 명확히 처리 가능.

### 우선순위
- **Low** - 기능 동작에 문제 없음

---

## Issue #3: refactor(common-model): 데이터 모델 개선

### 개요
common-model 모듈의 데이터 모델 개선 작업입니다.

### 개선 항목

#### 1. NotificationHistory 필수 필드 non-null로 변경 검토
**파일**: `common-model/src/main/kotlin/com/rep/model/NotificationHistory.kt`

현재 모든 필드가 nullable입니다:
```kotlin
data class NotificationHistory(
    val notificationId: String? = null,
    val userId: String? = null,
    val productId: String? = null,
    // ...
)
```

`notificationId`, `userId`, `type`, `status`, `sentAt` 등 필수 필드는 non-null로 변경 검토.
JSON 역직렬화 호환성 확인 필요.

#### 2. ProductDocument.isValid()의 price >= 0 조건 확인
**파일**: `common-model/src/main/kotlin/com/rep/model/ProductDocument.kt`

```kotlin
fun isValid(): Boolean = productId.isNotBlank() && productName.isNotBlank()
                         && category.isNotBlank() && price >= 0
```

`price == 0`인 경우를 유효하게 처리할지 비즈니스 검토 필요.
(무료 상품 허용? 또는 `price > 0`으로 변경?)

### 우선순위
- **Low** - 기능 동작에 문제 없음

---

## Issue #4: infra: 프로덕션 환경 준비

### 개요
프로덕션 배포 전 인프라 개선 작업입니다.

### 개선 항목

#### 1. Jaeger 영구 저장소 설정
**파일**: `docker/docker-compose.yml`

현재 Jaeger가 메모리 저장소를 사용합니다.
프로덕션에서는 Elasticsearch 또는 Cassandra 백엔드 연결 필요.

```yaml
# 현재 (메모리)
jaeger:
  image: jaegertracing/all-in-one:1.52

# 프로덕션 권장
jaeger:
  environment:
    - SPAN_STORAGE_TYPE=elasticsearch
    - ES_SERVER_URLS=http://elasticsearch:9200
```

#### 2. Loki/Prometheus 보존 기간 조정
**파일**: `docker/loki/loki-config.yaml`, `docker/docker-compose.yml`

| 서비스 | 현재 | 프로덕션 권장 |
|--------|------|--------------|
| Loki | 7일 (168h) | 30일+ |
| Prometheus | 15일 | 30일+ |

#### 3. Frontend 헬스체크 엔드포인트 확인
**파일**: `frontend/`

Docker 환경에서 frontend 컨테이너의 헬스체크가 제대로 동작하는지 확인 필요.
현재 `curl -f http://localhost:3000` 사용 중.

### 우선순위
- **Medium** - 프로덕션 배포 전 필요

---

## 생성 방법

GitHub CLI가 설치되어 있다면:
```bash
gh issue create --title "제목" --body "내용" --label "enhancement"
```

또는 GitHub 웹에서 직접 생성해주세요.
