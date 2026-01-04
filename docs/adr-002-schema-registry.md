# ADR-002: Schema Registry 직렬화 포맷 선택

## 상태 (Status)
**채택됨 (Accepted)**

## 배경 (Context)

REP-Engine은 Kafka를 통해 다양한 이벤트(유저 행동, 상품 변동, 알림)를 전달합니다. 마이크로서비스 간 메시지 호환성과 스키마 진화(Schema Evolution)를 안전하게 관리하기 위해 Schema Registry 도입이 필요합니다.

### 해결해야 할 문제

1. **스키마 호환성:** Producer와 Consumer가 독립적으로 배포될 때 메시지 포맷 불일치 방지
2. **스키마 진화:** 필드 추가/삭제 시 기존 Consumer가 깨지지 않도록 관리
3. **문서화:** 스키마 자체가 데이터 계약(Data Contract) 역할 수행
4. **성능:** 직렬화/역직렬화 오버헤드 최소화

## 검토한 대안 (Alternatives Considered)

### Option 1: JSON (Plain)

```json
{"userId": "U123", "productId": "P456", "action": "CLICK"}
```

| 장점 | 단점 |
|-----|-----|
| 사람이 읽기 쉬움 | 스키마 강제 불가 (런타임 에러 위험) |
| 별도 도구 불필요 | 메시지 크기 큼 (필드명 반복) |
| 디버깅 용이 | 스키마 진화 관리 어려움 |

### Option 2: JSON Schema

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "userId": {"type": "string"},
    "productId": {"type": "string"}
  },
  "required": ["userId", "productId"]
}
```

| 장점 | 단점 |
|-----|-----|
| JSON 호환 (가독성 유지) | Avro 대비 메시지 크기 큼 |
| Schema Registry 지원 | 호환성 규칙이 Avro보다 느슨함 |
| 점진적 도입 용이 | 복잡한 타입 표현 제한적 |

### Option 3: Apache Avro

```avro
{
  "type": "record",
  "name": "UserActionEvent",
  "namespace": "com.rep.event",
  "fields": [
    {"name": "userId", "type": "string"},
    {"name": "productId", "type": "string"},
    {"name": "action", "type": {"type": "enum", "name": "ActionType", "symbols": ["VIEW", "CLICK", "SEARCH", "PURCHASE"]}}
  ]
}
```

| 장점 | 단점 |
|-----|-----|
| 스키마 진화 규칙 명확 (BACKWARD, FORWARD, FULL) | 별도 스키마 파일 관리 필요 |
| 바이너리 포맷으로 메시지 크기 작음 | 메시지 자체만으론 해석 불가 (스키마 필요) |
| Confluent 생태계 완벽 지원 | 학습 곡선 존재 |
| 코드 생성 지원 (타입 안전성) | |

### Option 4: Protocol Buffers (Protobuf)

```protobuf
syntax = "proto3";
message UserActionEvent {
  string user_id = 1;
  string product_id = 2;
  ActionType action = 3;
}
```

| 장점 | 단점 |
|-----|-----|
| 가장 작은 메시지 크기 | Kafka 생태계 통합이 Avro보다 약함 |
| gRPC와 호환 | 스키마 진화 규칙이 Avro와 다름 |
| 다양한 언어 지원 | Confluent Schema Registry 지원이 상대적으로 늦음 |

## 결정 (Decision)

**Apache Avro + Confluent Schema Registry**를 채택합니다.

### 선택 이유

1. **Kafka 생태계 표준:** Confluent 플랫폼의 기본 직렬화 포맷으로, 가장 성숙한 통합 지원
2. **엄격한 호환성 규칙:** BACKWARD_TRANSITIVE 정책으로 Consumer가 항상 이전 버전 메시지를 읽을 수 있음
3. **효율적인 바이너리 포맷:** JSON 대비 약 30-50% 메시지 크기 감소
4. **Kotlin 통합:** `avro-kotlin` 플러그인으로 data class 자동 생성

## 상세 설계 (Implementation)

### 스키마 호환성 정책

| 토픽 | 호환성 모드 | 이유 |
|-----|-----------|-----|
| user.action.v1 | BACKWARD_TRANSITIVE | Consumer가 먼저 배포되어도 안전 |
| product.inventory.v1 | BACKWARD_TRANSITIVE | 동일 |
| notification.push.v1 | FULL_TRANSITIVE | 양방향 호환 필요 (재처리 시나리오) |

### 스키마 진화 규칙

**허용되는 변경:**
- 새 필드 추가 (default 값 필수)
- optional 필드를 required로 변경 (default 있을 때)

**금지되는 변경:**
- 필드 삭제 (BACKWARD 위반)
- 필드 타입 변경
- 필드명 변경 (alias 사용으로 우회 가능)

### 스키마 파일 구조

```
src/main/avro/
├── user-action-event.avsc
├── product-inventory-event.avsc
└── notification-event.avsc
```

### UserActionEvent.avsc 예시

```json
{
  "type": "record",
  "name": "UserActionEvent",
  "namespace": "com.rep.event.user",
  "doc": "유저 행동 이벤트 - 클릭, 검색, 구매 등",
  "fields": [
    {
      "name": "traceId",
      "type": "string",
      "doc": "분산 추적을 위한 고유 ID"
    },
    {
      "name": "userId",
      "type": "string",
      "doc": "유저 고유 식별자"
    },
    {
      "name": "productId",
      "type": "string",
      "doc": "상품 고유 식별자"
    },
    {
      "name": "category",
      "type": "string",
      "doc": "상품 카테고리"
    },
    {
      "name": "actionType",
      "type": {
        "type": "enum",
        "name": "ActionType",
        "symbols": ["VIEW", "CLICK", "SEARCH", "PURCHASE", "ADD_TO_CART", "WISHLIST"]
      },
      "doc": "유저 행동 유형"
    },
    {
      "name": "metadata",
      "type": ["null", {"type": "map", "values": "string"}],
      "default": null,
      "doc": "추가 메타데이터 (검색어, 소스 페이지 등)"
    },
    {
      "name": "timestamp",
      "type": "long",
      "logicalType": "timestamp-millis",
      "doc": "이벤트 발생 시각 (epoch millis)"
    }
  ]
}
```

### Gradle 설정

```kotlin
plugins {
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1"
}

dependencies {
    implementation("io.confluent:kafka-avro-serializer:7.5.0")
    implementation("org.apache.avro:avro:1.11.3")
}

avro {
    isCreateSetters.set(false)
    isCreateOptionalGetters.set(true)
    fieldVisibility.set("PRIVATE")
    outputCharacterEncoding.set("UTF-8")
}
```

## 결과 (Consequences)

### 긍정적 효과
- 컴파일 타임에 스키마 오류 감지
- 메시지 크기 감소로 Kafka 처리량 향상
- 스키마 버전 관리로 무중단 배포 가능

### 부정적 효과 / 트레이드오프
- 디버깅 시 메시지 내용 직접 확인 어려움 → Kafka UI 도구로 보완
- 스키마 파일 관리 오버헤드 → CI/CD 파이프라인에 스키마 검증 단계 추가
- 개발 초기 학습 비용 → 템플릿 및 예제 코드 제공으로 완화

## 관련 문서
- [ADR-001: 동시성 처리 전략](./adr-001-concurrency-strategy.md)
- [Infrastructure: 인프라 구성](./infrastructure.md)