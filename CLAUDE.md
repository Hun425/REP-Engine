# REP-Engine 프로젝트 컨텍스트

## 프로젝트 개요
실시간 유저 행동 기반 개인화 추천 및 알림 엔진. Kafka + Elasticsearch + Redis를 활용한 이벤트 기반 아키텍처.

## 핵심 기술 스택
- **Language:** Kotlin 2.x (JVM 25)
- **Framework:** Spring Boot 3.4+
- **Messaging:** Apache Kafka + Confluent Schema Registry (Avro)
- **Search:** Elasticsearch 8.11+ (KNN Vector Search)
- **Cache:** Redis 7.2+
- **Embedding:** multilingual-e5-base (384 dims)

## 프로젝트 구조 (모듈)
| 모듈 | 용도 |
|-----|------|
| `common-avro` | Kafka 메시지 스키마 (Avro) |
| `common-model` | ES 문서 + Redis 데이터 모델 (공유) |
| `simulator` | 트래픽 시뮬레이터 (Phase 1) |
| `behavior-consumer` | Kafka Consumer, ES Indexing, 취향 벡터 갱신 (Phase 2) |
| `recommendation-api` | 추천 API (Phase 3) |
| `notification-service` | 가격/재고 변동 감지, 알림 발송 (Phase 4) |
| `frontend` | React 통합 대시보드 (Phase 6) |

## 문서 구조 (docs/)

### 필수 참고 문서
| 문서 | 용도 | 구현 시 참고 |
|-----|------|-------------|
| `마스터 설계서.md` | 전체 아키텍처, 기술 스택 | 새 컴포넌트 추가 시 |
| `infrastructure.md` | Docker Compose, 환경 설정 | 인프라 변경 시 |

### ADR (Architecture Decision Records)
| 문서 | 결정 사항 | 구현 시 준수 |
|-----|----------|-------------|
| `adr-001-concurrency-strategy.md` | Coroutines + Virtual Threads | 비동기 코드 작성 시 |
| `adr-002-schema-registry.md` | Avro 직렬화 | Kafka 메시지 스키마 정의 시 |
| `adr-003-embedding-model.md` | multilingual-e5-base | 벡터 생성 로직 구현 시 |
| `adr-004-vector-storage.md` | ES + Redis Hybrid | 벡터 저장/조회 구현 시 |

### Phase 문서
| 문서 | 범위 |
|-----|------|
| `phase 1.md` | 트래픽 시뮬레이터 |
| `phase 2.md` | Kafka Consumer, ES Bulk Indexing |
| `phase 3.md` | KNN 검색, 추천 API |
| `phase 4.md` | 가격 변동 감지, 알림 발송 |
| `phase 5.md` | 모니터링, 테스트 |
| `phase 6.md` | React 프론트엔드 대시보드 |

## 구현 시 필수 체크리스트

### 코드 작성 전
- [ ] 관련 Phase 문서 읽기
- [ ] 해당 ADR 결정 사항 확인
- [ ] 마스터 설계서의 아키텍처와 일치하는지 확인

### Kafka 메시지 추가 시
- [ ] `adr-002-schema-registry.md` 참고
- [ ] Avro 스키마 정의 (.avsc 파일)
- [ ] BACKWARD_TRANSITIVE 호환성 준수
- [ ] `infrastructure.md`의 토픽 목록 업데이트

### 벡터 관련 구현 시
- [ ] 벡터 차원: 384 (multilingual-e5-base)
- [ ] 상품 벡터: ES `product_index`에 저장
- [ ] 유저 취향 벡터: Redis 캐시 (24시간 TTL) + ES 백업
- [ ] EMA 가중치:
  - PURCHASE=0.5 (강한 신호)
  - ADD_TO_CART=0.3, CLICK=0.3 (중간 강도 신호)
  - SEARCH=0.2 (중간 신호)
  - VIEW=0.1, WISHLIST=0.1 (약한 신호)

### ES 인덱스 추가/변경 시
- [ ] `infrastructure.md`의 init-indices.sh 업데이트
- [ ] 마스터 설계서의 인덱스 목록 업데이트

### 새로운 기술 결정 시
- [ ] ADR 문서 추가 (adr-00X-*.md)
- [ ] 마스터 설계서 관련 문서 섹션 업데이트

## 주요 설정값

### Kafka 토픽
| 토픽 | 파티션 | 용도 |
|-----|--------|------|
| user.action.v1 | 12 | 유저 행동 이벤트 |
| product.inventory.v1 | 3 | 재고/가격 변동 |
| notification.push.v1 | 6 | 알림 발송 |

### ES Bulk Indexing
- bulkSize: 500
- Kafka fetch.max.wait.ms: 500ms (배치 수집 대기 시간)

### KNN 검색
- k: 10 (기본값)
- num_candidates: k * 10 (동적 계산)
- similarity: cosine

## 문서 최신화 규칙

1. **코드 변경 시:** 관련 Phase 문서 업데이트
2. **기술 결정 변경 시:** ADR 문서 추가 또는 수정
3. **인프라 변경 시:** infrastructure.md 업데이트
4. **아키텍처 변경 시:** 마스터 설계서 업데이트

> 문서와 코드가 불일치하면 문서를 먼저 업데이트하고 코드를 수정할 것.

## Git 브랜치 전략

### 규칙
- **main 브랜치에 직접 커밋 금지** — 항상 브랜치를 생성하고 PR로 머지
- PR 본문에 변경 사유, 수정 내역, 테스트 방법 기록

### 브랜치 네이밍
| 접두사 | 용도 | 예시 |
|--------|------|------|
| `feat/` | 새 기능 추가 | `feat/notification-history-api` |
| `fix/` | 버그 수정 | `fix/proxy-port-mismatch` |
| `chore/` | 설정, 문서, 의존성 등 | `chore/gitignore-cleanup` |
| `refactor/` | 코드 리팩토링 | `refactor/preference-updater` |
| `perf/` | 성능 최적화 | `perf/jvm-gc-tuning` |

### 워크플로우
```
1. main에서 브랜치 생성: git checkout -b feat/xxx
2. 작업 후 커밋
3. 원격에 push: git push -u origin feat/xxx
4. PR 생성 (main ← feat/xxx)
5. 리뷰 후 머지
```

## 서비스 포트 매핑

| 서비스 | 로컬 포트 | Docker 포트 | 비고 |
|--------|----------|------------|------|
| Simulator | 8084 | 8084 | |
| Behavior Consumer | 8085 | 8085 | Schema Registry(8081)와 충돌 방지 |
| Recommendation API | 8080 | 8082 | |
| Notification Service | 8083 | 8083 | |
| Frontend (dev) | 5173 | 3001 | Vite / Nginx |
| Schema Registry | 8081 | 8081 | |
| Elasticsearch | 9200 | 9200 | |
| Redis | 6379 | 6379 | |
| Embedding Service | 8000 | 8000 | |
| Prometheus | 9090 | 9090 | |
| Grafana | 3000 | 3000 | |

## 실행 방식
- **인프라:** Docker Compose (`docker/docker-compose.yml`)
- **Spring Boot 앱 4개:** 로컬(IDE/터미널)에서 직접 실행
- **프론트 개발:** `npm run dev` (Vite, localhost:5173 → 백엔드 프록시)
- **프론트 프로덕션:** Docker Nginx (localhost:3001 → `host.docker.internal`로 백엔드 프록시)

## 주요 컴포넌트

### EmbeddingClient (Phase 3 연동)
- **위치:** `behavior-consumer/src/main/kotlin/com/rep/consumer/client/EmbeddingClient.kt`
- **용도:** Python Embedding Service 호출 (상품 설명 → 384차원 벡터)
- **현재 상태:** Phase 2에서 인터페이스 구현됨, Phase 3에서 실제 서비스 연동 예정
- **HTTP 엔드포인트:** `POST /embed` (배치 텍스트 → 벡터 변환)