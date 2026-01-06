# common-avro 모듈 설명서

## 이 모듈이 하는 일 (한 줄 요약)

**Kafka로 주고받는 메시지의 "형식"을 정의하는 모듈**

---

## 비유로 이해하기

편지를 보낼 때 봉투에 "보내는 사람", "받는 사람", "우편번호" 칸이 정해져 있죠?
이 모듈은 **우리 시스템에서 주고받는 메시지의 칸(형식)을 정의**합니다.

예를 들어 "유저가 상품을 클릭했다"는 메시지를 보낼 때:
- 어떤 유저가? (userId)
- 어떤 상품을? (productId)
- 언제? (timestamp)
- 무슨 행동을? (actionType)

이런 정보들을 어떤 순서와 형식으로 담을지 미리 약속해두는 겁니다.

---

## 파일 구조

```
common-avro/
├── build.gradle.kts                    # 빌드 설정 파일
└── src/main/avro/
    ├── user-action-event.avsc          # 유저 행동 이벤트 스키마
    ├── product-inventory-event.avsc    # 상품 재고/가격 변동 스키마
    └── notification-event.avsc         # 알림 발송 이벤트 스키마
```

---

## 핵심 파일 설명

### 1. user-action-event.avsc

**위치**: `src/main/avro/user-action-event.avsc`

이 파일은 **Avro 스키마** 파일입니다. JSON 형식으로 작성되어 있어요.

```json
{
  "type": "record",
  "name": "UserActionEvent",
  "namespace": "com.rep.event.user",
  "fields": [
    {"name": "traceId", "type": "string"},
    {"name": "userId", "type": "string"},
    {"name": "productId", "type": "string"},
    {"name": "category", "type": "string"},
    {
      "name": "actionType",
      "type": {
        "type": "enum",
        "name": "ActionType",
        "symbols": ["VIEW", "CLICK", "SEARCH", "PURCHASE", "ADD_TO_CART", "WISHLIST"]
      }
    },
    {
      "name": "metadata",
      "type": ["null", {"type": "map", "values": "string"}],
      "default": null
    },
    {"name": "timestamp", "type": {"type": "long", "logicalType": "timestamp-millis"}}
  ]
}
```

#### 각 필드가 뭔지 쉽게 설명

| 필드명 | 설명 | 예시 |
|--------|------|------|
| `traceId` | 이 메시지의 고유 번호 (택배 송장번호 같은 것) | "evt-abc123" |
| `userId` | 행동한 유저의 ID | "USER-000001" |
| `productId` | 관련된 상품 ID | "ELECTRONICS-0042" |
| `category` | 상품 카테고리 | "ELECTRONICS" |
| `actionType` | 어떤 행동인지 | "VIEW", "CLICK", "PURCHASE" 등 |
| `metadata` | 추가 정보 (선택, null 가능) | {"searchQuery": "노트북"} 또는 null |
| `timestamp` | 언제 발생했는지 (밀리초 단위) | 1704067200000 |

#### actionType 종류

| 타입 | 의미 | 가중치 |
|------|------|--------|
| `VIEW` | 상품 페이지를 봤다 | 약함 (0.1) |
| `CLICK` | 상품을 클릭했다 | 중간 (0.3) |
| `SEARCH` | 검색했다 | 중간 (0.2) |
| `ADD_TO_CART` | 장바구니에 담았다 | 강함 (0.3) |
| `WISHLIST` | 찜 목록에 추가했다 | 약함 (0.1) |
| `PURCHASE` | 구매했다 | 가장 강함 (0.5) |

---

### 2. build.gradle.kts

**이 파일이 하는 일**: Avro 스키마 파일(.avsc)을 **자바/코틀린 클래스로 자동 변환**해줍니다.

```kotlin
plugins {
    kotlin("jvm")
    id("com.github.davidmc24.gradle.plugin.avro")  // Avro 플러그인
}

avro {
    isCreateSetters.set(false)          // setter 메서드 안 만듦 (불변 객체)
    fieldVisibility.set("PRIVATE")      // 필드는 private으로
    stringType.set("String")            // 문자열은 Java String 사용
}
```

**빌드하면 생기는 것**:
- `UserActionEvent.java` 클래스가 자동 생성됨
- 이 클래스를 코드에서 `import com.rep.event.user.UserActionEvent`로 사용

---

## 왜 Avro를 쓰나요?

### 1. 용량이 작다
JSON은 필드명을 매번 보내지만, Avro는 스키마를 따로 저장하고 데이터만 보냅니다.
```
JSON:  {"userId": "U001", "productId": "P001", "actionType": "CLICK"}  (약 60바이트)
Avro:  [U001][P001][CLICK]  (약 15바이트)
```

### 2. 스키마 호환성 관리
Schema Registry라는 서버가 스키마 버전을 관리해줍니다.
- 버전 1: userId, productId만 있음
- 버전 2: timestamp 필드 추가
- 옛날 버전으로 보낸 메시지도 새 버전에서 읽을 수 있음!

### 3. 타입 안전성
JSON은 숫자를 문자열로 보내도 에러 안 남.
Avro는 스키마에 `long`이라고 했으면 무조건 숫자만 가능 → 버그 방지

---

## 이 모듈을 사용하는 곳

```
[simulator 모듈]
     │
     │  UserActionEvent 객체 생성해서 Kafka로 전송
     ▼
[Kafka 토픽: user.action.v1]
     │
     │  UserActionEvent 객체를 받아서 처리
     ▼
[behavior-consumer 모듈]
```

1. **simulator**: `UserActionEvent` 객체를 만들어서 Kafka로 보냄
2. **behavior-consumer**: Kafka에서 `UserActionEvent` 객체를 받아서 처리

---

## 자주 하는 실수와 해결법

### 실수 1: 스키마 파일 수정 후 빌드 안 함
```
문제: .avsc 파일을 수정했는데 코드에서 새 필드가 안 보여요
해결: ./gradlew :common-avro:build 실행
```

### 실수 2: 호환성 깨뜨리는 수정
```
문제: 기존 필드 삭제하거나 타입 변경 → 옛날 메시지 못 읽음
해결: 필드 추가만 하고, 기본값 설정하기
     {"name": "newField", "type": "string", "default": ""}
```

---

## 핵심 요약

| 항목 | 내용 |
|------|------|
| 역할 | Kafka 메시지 형식(스키마) 정의 |
| 핵심 파일 | `user-action-event.avsc` |
| 생성되는 클래스 | `com.rep.event.user.UserActionEvent` |
| 사용하는 모듈 | simulator, behavior-consumer |
| 왜 Avro? | 작은 용량, 스키마 버전 관리, 타입 안전성 |
