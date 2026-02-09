# common-model 모듈 설명서

## 이 모듈이 하는 일 (한 줄 요약)

**Elasticsearch와 Redis에 저장하는 데이터의 "형태"를 정의하는 모듈**

---

## 비유로 이해하기

엑셀 파일을 만들 때 "이름", "나이", "주소" 같은 열(컬럼)을 먼저 정하죠?
이 모듈은 **데이터베이스에 저장할 데이터의 열(필드)을 미리 정의**합니다.

예를 들어 상품 정보를 저장할 때:
- 상품명은 뭐야?
- 가격은 얼마야?
- 카테고리는 뭐야?

이런 정보들을 어떻게 저장할지 정해두는 겁니다.

---

## 파일 구조

```
common-model/
├── build.gradle.kts
└── src/main/kotlin/com/rep/model/
    ├── ProductDocument.kt         # 상품 정보 (ES에 저장)
    ├── UserPreferenceData.kt      # 유저 취향 (Redis에 저장)
    ├── UserPreferenceDocument.kt  # 유저 취향 (ES 백업용)
    └── NotificationHistory.kt     # 알림 발송 이력 (ES에 저장)
```

---

## 핵심 파일 상세 설명

### 1. ProductDocument.kt

**역할**: Elasticsearch의 `product_index`에 저장되는 상품 정보

```kotlin
data class ProductDocument(
    // 필수 필드 (JSON 역직렬화를 위해 빈 문자열 기본값)
    val productId: String = "",           // 상품 고유 ID
    val productName: String = "",         // 상품명 (예: "삼성 갤럭시 S24")
    val category: String = "",            // 대분류 (예: "ELECTRONICS")
    val price: Float = 0f,                // 가격 (예: 1200000.0)

    // 선택 필드 (nullable)
    val subCategory: String? = null,      // 소분류 (예: "스마트폰")
    val stock: Int? = null,               // 재고 수량 (예: 50)
    val brand: String? = null,            // 브랜드 (예: "Samsung")
    val description: String? = null,      // 상품 설명
    val tags: List<String>? = null,       // 태그 목록 (예: ["신상품", "베스트"])
    val productVector: List<Float>? = null,  // 상품 벡터 (768개 숫자)
    val createdAt: String? = null,        // 등록일
    val updatedAt: String? = null         // 수정일
) {
    /** 필수 필드가 유효한지 검증 */
    fun isValid(): Boolean = productId.isNotBlank() && productName.isNotBlank()
                             && category.isNotBlank() && price >= 0

    /** 상품 벡터가 존재하는지 확인 */
    fun hasVector(): Boolean = productVector != null && productVector.isNotEmpty()
}
```

> **참고**: 필수 필드(`productId`, `productName`, `category`, `price`)는 JSON 역직렬화 시 누락 방지를 위해 기본값을 가지며, `isValid()` 메서드로 실제 유효성을 검증합니다.

#### 상품 벡터(productVector)란?

상품의 특징을 **768개의 숫자**로 표현한 것입니다.

```
"고급 가죽 지갑" → [0.12, -0.34, 0.56, ..., 0.78]  (768개 숫자)
"명품 가죽 핸드백" → [0.11, -0.32, 0.54, ..., 0.76]  (비슷한 숫자들!)
"운동화" → [0.89, 0.12, -0.45, ..., -0.23]  (다른 숫자들)
```

**비슷한 상품은 비슷한 숫자를 가집니다!**
이걸 이용해서 "이 상품과 비슷한 상품 찾기"를 할 수 있어요.

---

### 2. UserPreferenceData.kt

**역할**: Redis에 저장되는 유저의 취향 데이터

```kotlin
data class UserPreferenceData(
    val preferenceVector: List<Float>,    // 유저 취향 벡터 (768개 숫자)
    val actionCount: Int = 1,             // 누적 행동 수
    val updatedAt: Long = System.currentTimeMillis(),  // 마지막 업데이트 시간
    val version: Long = 1                 // 버전 (Optimistic Locking용)
) {
    /** KNN 검색용 FloatArray 변환 */
    fun toFloatArray(): FloatArray = preferenceVector.toFloatArray()
}
```

> **version 필드**: Redis-ES 동기화 시 race condition 방지를 위한 Optimistic Locking에 사용됩니다.

#### 왜 Redis에 저장하나요?

| 저장소 | 특징 | 용도 |
|--------|------|------|
| Redis | 엄청 빠름 (메모리 저장) | 자주 조회하는 데이터 |
| Elasticsearch | 빠름 (디스크 저장) | 검색이 필요한 데이터 |

유저 취향 벡터는 추천할 때마다 조회해야 하니까 **빠른 Redis에 저장**합니다.

#### 취향 벡터는 어떻게 만들어지나요?

```
1. 유저가 "가죽 지갑"을 봤다
   → 가죽 지갑 벡터: [0.12, -0.34, 0.56, ...]

2. 유저의 취향 벡터 업데이트:
   새 취향 = 기존 취향 × 0.9 + 가죽 지갑 벡터 × 0.1
   (VIEW는 가중치가 0.1로 약함)

3. 유저가 "가죽 핸드백"을 구매했다
   새 취향 = 기존 취향 × 0.5 + 가죽 핸드백 벡터 × 0.5
   (PURCHASE는 가중치가 0.5로 강함)
```

점점 유저가 좋아하는 상품 쪽으로 벡터가 이동합니다!

---

### 3. UserPreferenceDocument.kt

**역할**: Elasticsearch에 저장되는 유저 취향 (백업용)

```kotlin
data class UserPreferenceDocument(
    val userId: String? = null,           // 유저 ID
    val preferenceVector: List<Float>? = null,  // 취향 벡터
    val actionCount: Int? = null,         // 누적 행동 수
    val updatedAt: Long? = null           // 마지막 업데이트 시간
)
```

#### Redis랑 뭐가 다른가요?

| 항목 | Redis (Primary) | ES (Backup) |
|------|-----------------|-------------|
| 역할 | 메인 저장소 | 백업 저장소 |
| 조회 시 | 먼저 여기서 찾음 | Redis에 없을 때만 |
| 데이터 보존 | 24시간 후 삭제 (TTL) | 영구 보존 |

**시나리오**:
1. 유저 취향 조회 요청
2. Redis에서 찾기 → 있으면 반환 (빠름!)
3. Redis에 없으면 → ES에서 찾기 → Redis에 다시 저장 → 반환

---

### 4. NotificationHistory.kt

**역할**: Elasticsearch의 `notification_history_index`에 저장되는 알림 발송 이력

```kotlin
data class NotificationHistory(
    val notificationId: String? = null,   // 알림 고유 ID
    val userId: String? = null,           // 대상 유저 ID
    val productId: String? = null,        // 관련 상품 ID
    val type: String? = null,             // 알림 유형 (PRICE_DROP, BACK_IN_STOCK 등)
    val title: String? = null,            // 알림 제목
    val body: String? = null,             // 알림 본문
    val channels: List<String>? = null,   // 발송 채널 (PUSH, SMS, IN_APP)
    val status: String? = null,           // 발송 상태 (SendStatus enum 값)
    val sentAt: Instant? = null           // 발송 시각
)

/** 알림 발송 상태 */
enum class SendStatus {
    SENT,           // 발송 완료
    FAILED,         // 발송 실패
    RATE_LIMITED,   // Rate Limit으로 차단됨
    USER_OPTED_OUT  // 유저가 알림 수신 거부
}
```

#### 알림 유형 (type)

| 값 | 설명 |
|-----|------|
| `PRICE_DROP` | 가격 인하 알림 |
| `BACK_IN_STOCK` | 재입고 알림 |
| `DAILY_RECOMMENDATION` | 일일 추천 알림 |

#### 발송 채널 (channels)

| 값 | 설명 |
|-----|------|
| `PUSH` | 모바일 푸시 알림 |
| `SMS` | 문자 메시지 |
| `IN_APP` | 인앱 알림 |

---

## 데이터 흐름도

```
┌─────────────────────────────────────────────────────────┐
│                    상품 데이터 흐름                      │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  [seed_products.py]                                      │
│       │                                                  │
│       │ 상품 정보 + 벡터 생성                            │
│       ▼                                                  │
│  [Elasticsearch: product_index]                          │
│       │                                                  │
│       │ ProductDocument 형태로 저장                      │
│       ▼                                                  │
│  [recommendation-api] → KNN 검색으로 비슷한 상품 찾기    │
│                                                          │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                   유저 취향 데이터 흐름                   │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  [behavior-consumer]                                     │
│       │                                                  │
│       │ 유저 행동 이벤트 처리                            │
│       ▼                                                  │
│  [Redis: user:preference:{userId}]  ←─ 메인 저장         │
│       │                                                  │
│       │ 비동기 백업                                      │
│       ▼                                                  │
│  [Elasticsearch: user_preference_index] ←─ 백업용        │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## 이 모듈을 사용하는 곳

| 모듈 | 사용하는 클래스 | 용도 |
|------|----------------|------|
| behavior-consumer | `ProductDocument` | 상품 벡터 조회 |
| behavior-consumer | `UserPreferenceData` | 유저 취향 Redis 저장 |
| behavior-consumer | `UserPreferenceDocument` | 유저 취향 ES 백업 |
| recommendation-api | `ProductDocument` | KNN 검색 결과 매핑 |
| recommendation-api | `UserPreferenceData` | 유저 취향 Redis 조회 |
| recommendation-api | `UserPreferenceDocument` | 유저 취향 ES 폴백 |
| notification-service | `ProductDocument` | 상품 정보 조회 (알림 내용 생성) |
| notification-service | `NotificationHistory` | 알림 발송 이력 ES 저장 |

---

## data class란?

코틀린에서 `data class`는 **데이터를 담기 위한 특별한 클래스**입니다.

```kotlin
// 일반 클래스로 만들면 이렇게 길어짐
class Person {
    private var name: String = ""
    private var age: Int = 0

    fun getName(): String = name
    fun setName(name: String) { this.name = name }
    fun getAge(): Int = age
    fun setAge(age: Int) { this.age = age }

    override fun equals(other: Any?): Boolean { ... }
    override fun hashCode(): Int { ... }
    override fun toString(): String { ... }
}

// data class로 만들면 한 줄!
data class Person(val name: String, val age: Int)
```

`data class`는 자동으로:
- getter/setter 생성
- equals(), hashCode(), toString() 생성
- copy() 메서드 생성

---

## 핵심 요약

| 항목 | 내용 |
|------|------|
| 역할 | ES, Redis 데이터 모델 정의 |
| ProductDocument | 상품 정보 + 벡터 (ES 저장) |
| UserPreferenceData | 유저 취향 벡터 (Redis 저장) |
| UserPreferenceDocument | 유저 취향 벡터 (ES 백업) |
| NotificationHistory | 알림 발송 이력 (ES 저장) |
| SendStatus | 알림 발송 상태 enum |
| 벡터 차원 | 768개 (multilingual-e5-base 모델) |
| 사용 모듈 | behavior-consumer, recommendation-api, notification-service |
