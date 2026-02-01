# REP-Engine 모듈별 코드 설명서

> 이 폴더는 각 모듈의 코드를 상세히 설명한 문서들입니다.

---

## 문서 목록

| 번호 | 문서 | 모듈 | 한 줄 설명 |
|------|------|------|-----------|
| 01 | [common-avro](./01-common-avro.md) | common-avro | Kafka 메시지 형식 정의 |
| 02 | [common-model](./02-common-model.md) | common-model | ES/Redis 데이터 모델 정의 |
| 03 | [simulator](./03-simulator.md) | simulator | 가짜 유저 행동 데이터 생성 |
| 04 | [behavior-consumer](./04-behavior-consumer.md) | behavior-consumer | Kafka→ES 저장 + 취향 업데이트 |
| 05 | [recommendation-api](./05-recommendation-api.md) | recommendation-api | 개인화 추천 API |
| 06 | [notification-service](./06-notification-service.md) | notification-service | 가격 변동 감지 + 알림 발송 |
| 07 | [frontend](./07-frontend.md) | frontend | React 통합 대시보드 (Phase 6) |

---

## 권장 읽기 순서

```
1. common-avro     (메시지 형식부터 이해)
       ↓
2. common-model    (데이터 모델 이해)
       ↓
3. simulator       (데이터가 어떻게 생성되는지)
       ↓
4. behavior-consumer (데이터가 어떻게 처리되는지)
       ↓
5. recommendation-api (추천이 어떻게 동작하는지)
       ↓
6. notification-service (알림이 어떻게 발송되는지)
       ↓
7. frontend        (사용자가 어떻게 시스템을 사용하는지)
```

---

## 전체 시스템 흐름

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        REP-Engine 전체 흐름                             │
└─────────────────────────────────────────────────────────────────────────┘

┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  simulator   │     │   Kafka      │     │  behavior-   │
│              │ ──▶ │              │ ──▶ │  consumer    │
│ (Phase 1)    │     │ 토픽:        │     │ (Phase 2)    │
│              │     │ user.action  │     │              │
│ 가짜 유저    │     │ .v1          │     │ ES 저장 +    │
│ 행동 생성    │     │              │     │ 취향 업데이트│
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                  │
                     ┌────────────────────────────┴───────┐
                     │                                    │
                     ▼                                    ▼
             ┌──────────────┐                     ┌──────────────┐
             │Elasticsearch │                     │    Redis     │
             │              │                     │              │
             │ - 행동 로그  │                     │ - 취향 벡터  │
             │ - 상품 벡터  │                     │   (캐시)     │
             │ - 알림 이력  │                     │ - Rate Limit │
             └──────┬───────┘                     └──────┬───────┘
                    │                                    │
                    └────────────────┬───────────────────┘
                                     │
                    ┌────────────────┼────────────────┐
                    │                │                │
                    ▼                ▼                ▼
           ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
           │recommendation│ │notification- │ │   Kafka      │
           │   -api       │ │  service     │ │   토픽:      │
           │ (Phase 3)    │ │ (Phase 4)    │ │ product.     │
           │              │ │              │◀│ inventory.v1 │
           │ 개인화 추천  │ │ 가격/재입고  │ │              │
           │ API 제공     │ │ 알림 발송    │ │ (외부 시스템)│
           └──────┬───────┘ └──────┬───────┘ └──────────────┘
                  │                │
                  │                ▼
                  │       ┌──────────────┐
                  │       │   Kafka      │
                  │       │   토픽:      │
                  │       │ notification │
                  │       │ .push.v1     │
                  │       └──────┬───────┘
                  │              │
                  │              ▼
                  │       ┌──────────────┐
                  │       │ Push/SMS/    │
                  │       │ Email 발송   │
                  │       └──────┬───────┘
                  │              │
                  └──────┬───────┘
                         │
                         ▼
                ┌──────────────┐
                │   frontend   │  ◀── React 대시보드 (Phase 6)
                │  (통합 UI)   │
                │              │
                │ - 추천 검색  │
                │ - 모니터링   │
                │ - 시뮬제어   │
                └──────────────┘
```

---

## 각 모듈의 핵심 역할

### common-avro
- **뭘 하나요?** Kafka 메시지 형식을 정의합니다
- **왜 필요한가요?** 보내는 쪽과 받는 쪽이 같은 형식을 써야 통신이 됩니다
- **핵심 파일**: `user-action-event.avsc`

### common-model
- **뭘 하나요?** ES/Redis에 저장할 데이터 형태를 정의합니다
- **왜 필요한가요?** 여러 모듈이 같은 데이터 형태를 공유해야 합니다
- **핵심 클래스**: `ProductDocument`, `UserPreferenceData`

### simulator
- **뭘 하나요?** 테스트용 가짜 데이터를 생성합니다
- **왜 필요한가요?** 실제 유저 없이도 시스템을 테스트할 수 있습니다
- **핵심 클래스**: `TrafficSimulator`, `UserSession`

### behavior-consumer
- **뭘 하나요?** Kafka 메시지를 받아서 저장하고 취향을 분석합니다
- **왜 필요한가요?** 유저 행동을 기록해야 추천이 가능합니다
- **핵심 클래스**: `BehaviorEventListener`, `PreferenceVectorCalculator`

### recommendation-api
- **뭘 하나요?** "당신이 좋아할 상품"을 추천해줍니다
- **왜 필요한가요?** 최종 목표! 개인화 추천 서비스 제공
- **핵심 클래스**: `RecommendationService`, `PopularProductsCache`

### notification-service
- **뭘 하나요?** 가격 하락/재입고 시 관심 유저에게 알림 발송
- **왜 필요한가요?** 유저 재방문 유도, 전환율 향상
- **핵심 클래스**: `EventDetector`, `TargetResolver`, `NotificationRateLimiter`

### frontend
- **뭘 하나요?** 시스템 전체를 한눈에 보고 제어하는 대시보드
- **왜 필요한가요?** 운영자/개발자가 시스템 상태를 모니터링하고 테스트
- **핵심 기술**: React, TanStack Query, Zustand, Tailwind CSS

---

## 주요 기술 용어 정리

| 용어 | 쉬운 설명 |
|------|----------|
| Kafka | 메시지 전달 시스템 (우체국 같은 것) |
| Avro | 메시지 형식 (편지 봉투의 칸 같은 것) |
| Elasticsearch (ES) | 검색 특화 데이터베이스 |
| Redis | 초고속 캐시 (메모리에 저장) |
| 벡터 | 숫자 배열 [0.1, 0.3, ...] |
| KNN | K개의 가장 비슷한 것 찾기 |
| EMA | 지수 이동 평균 (새 값에 가중치 주기) |
| TTL | 자동 삭제 시간 (Time To Live) |
| DLQ | 실패 메시지 보관함 (Dead Letter Queue) |
| Cold Start | 신규 유저 문제 (취향 데이터 없음) |
| Rate Limit | 과다 요청 방지 (횟수 제한) |
| 집계 (Aggregation) | ES에서 데이터 그룹화/통계 |

---

## 문서 작성 규칙

1. **비유 먼저**: 기술 설명 전에 일상적인 비유로 시작
2. **그림으로 표현**: ASCII 다이어그램으로 흐름 시각화
3. **코드 + 설명**: 코드 블록 아래에 한 줄씩 설명
4. **실습 가능**: 실행/테스트 방법 포함
5. **핵심 요약**: 문서 끝에 표로 정리
