# REP-Engine 부하 테스트

k6 기반 부하 테스트 + Spring `stress` 프로파일 + Grafana 전용 대시보드.

## 사전 조건

1. Docker 인프라 가동
2. 4개 Spring Boot 앱 기동 (기본 프로파일)
3. 시뮬레이터로 데이터 시딩 완료 (최소 100유저, 2분 이상)

```bash
# 인프라 기동
docker compose -f docker/docker-compose.yml up -d

# 시뮬레이터 시딩 (100유저, 500ms 간격, 2분 후 정지)
curl -X POST "http://localhost:8084/api/v1/simulator/start?userCount=100&delayMillis=500"
# ... 2분 대기 ...
curl -X POST "http://localhost:8084/api/v1/simulator/stop"
```

## Quick Start

```bash
# 추천 API 부하 테스트
docker compose -f load-test/docker-compose.load-test.yml run k6 run /scripts/recommendation-load.js

# E2E 파이프라인 스트레스 테스트
docker compose -f load-test/docker-compose.load-test.yml run k6 run /scripts/pipeline-stress.js

# 알림 파이프라인 스트레스 테스트
docker compose -f load-test/docker-compose.load-test.yml run k6 run /scripts/notification-load.js
```

### 환경변수 오버라이드

```bash
# 시뮬레이터가 생성한 유저 수가 100이 아닌 경우
docker compose -f load-test/docker-compose.load-test.yml run \
  -e WARM_USER_MAX=500 \
  k6 run /scripts/recommendation-load.js

# 서비스 URL 변경
docker compose -f load-test/docker-compose.load-test.yml run \
  -e RECOMMENDATION_URL=http://host.docker.internal:8080 \
  k6 run /scripts/recommendation-load.js
```

## 테스트 시나리오

### 시나리오 1: E2E 파이프라인 스트레스 (`pipeline-stress.js`)

시뮬레이터 REST API로 유저 수를 단계적 증가시켜 behavior-consumer 파이프라인 한계점을 탐색한다.

| 단계 | 유저 수 | delay | 유지 시간 | 쿨다운 |
|------|---------|-------|----------|--------|
| 1 | 100 | 200ms | 2분 | 30초 |
| 2 | 500 | 200ms | 2분 | 30초 |
| 3 | 1,000 | 200ms | 2분 | 30초 |
| 4 | 2,000 | 200ms | 2분 | 30초 |
| 5 | 5,000 | 200ms | 2분 | - |

**관찰 대상:** Kafka consumer lag, ES bulk 성공/실패율, preference update 처리량

### 시나리오 2: 추천 API 부하 (`recommendation-load.js`)

KNN 검색 P99 레이턴시 한계점과 Redis 캐시 효과를 측정한다.

| 구간 | VU | 시간 |
|------|-----|------|
| Warm-up | 0→10 | 30초 |
| Ramp | 10→50 | 1분 |
| Load | 50→100 | 2분 |
| Peak | 100→200 | 2분 |
| Spike | 200→300 | 1분 |
| Cool-down | 300→0 | 30초 |

**요청 비율:** warm 유저 KNN 50% / popular 30% / cold start 20%

**임계값:** P95 < 500ms, P99 < 1000ms, 에러율 < 5%

### 시나리오 3: 알림 파이프라인 (`notification-load.js`)

인벤토리 이벤트 폭증 시 알림 처리량과 rate limiter 동작을 확인한다.

| Phase | 시간 | 동작 |
|-------|------|------|
| A | 0~30초 | 트래픽 시뮬레이터 500유저 (행동 데이터 축적) |
| B | 30초~2분30초 | 인벤토리 시뮬레이터 + 추천 API 20 VU |
| C | 2분30초~4분 | 인벤토리만 유지 |
| D | 4분~5분 | 전부 정지, 쿨다운 |

## stress 프로파일

기본값 대비 부하 테스트에 최적화된 파라미터. `--spring.profiles.active=stress`로 활성화.

### behavior-consumer

| 파라미터 | 기본값 | stress | 이유 |
|---------|--------|--------|------|
| `consumer.bulk-size` | 500 | 1000 | ES Bulk 요청 횟수 절반 감소 |
| `consumer.concurrency` | 3 | 6 | 12 파티션 중 6개 병렬 소비 |
| `consumer.max-retries` | 3 | 2 | 빠른 실패, DLQ 전환 |
| `consumer.retry-delay-ms` | 1000 | 500 | 재시도 간격 단축 |
| `max-poll-records` | 500 | 1000 | bulk-size에 맞춤 |
| `fetch.min.bytes` | 1024 | 4096 | 더 큰 배치 축적 |
| `fetch.max.wait.ms` | 500 | 1000 | 배치 축적 대기 시간 증가 |
| 로그 레벨 | DEBUG | INFO | 로그 노이즈 감소 |

### recommendation-api

| 파라미터 | 기본값 | stress | 이유 |
|---------|--------|--------|------|
| `popular-ttl-minutes` | 10 | 30 | 캐시 히트율 증가 |
| `global-cache-size` | 100 | 200 | 캐시 엔트리 증가 |
| `category-cache-size` | 50 | 100 | 카테고리별 캐시 증가 |

### notification-service

| 파라미터 | 기본값 | stress | 이유 |
|---------|--------|--------|------|
| `batch-size` | 100 | 200 | Kafka 배치 크기 증가 |
| `batch-delay-ms` | 50 | 20 | 배치 처리 속도 증가 |
| `daily-limit-per-user` | 10 | 50 | rate limit 완화 |
| `price-drop-threshold` | 10 | 5 | 더 많은 알림 트리거 |

### simulator

| 파라미터 | 기본값 | stress | 이유 |
|---------|--------|--------|------|
| `user-count` | 100 | 1000 | 10배 트래픽 |
| `delay-millis` | 1000 | 200 | 5배 빈도 |
| `inventory-interval-ms` | 5000 | 500 | 10배 인벤토리 이벤트 |
| `linger.ms` | 5 | 20 | 프로듀서 배치 효율 |
| `batch.size` | 16384 | 65536 | 4배 배치 크기 |

### 활성화 방법

```bash
# IDE: VM options
-Dspring.profiles.active=stress

# 터미널
java -jar behavior-consumer.jar --spring.profiles.active=stress

# 환경변수
SPRING_PROFILES_ACTIVE=stress
```

## Grafana 대시보드

`http://localhost:3000` → **"Load Test Results"** 대시보드

| Row | 내용 |
|-----|------|
| k6 Test Overview | Current VUs, Request Rate, HTTP Latency P95, Error Rate |
| Latency Distribution | HTTP Duration (P50/P95/P99), Rec API vs k6 레이턴시 비교 |
| Pipeline Throughput | Kafka Consumer Lag, ES Bulk Rate, Preference Update Rate |
| Resource Pressure | JVM Heap (전 서비스), Redis Memory + Commands, ES Index Size |
| Notification Pipeline | Notification Rate, DLQ Messages |

## 트러블슈팅 시나리오

> 아래 시나리오는 실제 부하 테스트 후 결과를 기반으로 채워넣는 템플릿입니다.

### Story 1: Consumer Lag 폭증

**증상:** pipeline-stress 2000유저 단계에서 `kafka_consumergroup_lag`가 지속 증가

**원인 분석:**
- Grafana에서 consumer lag 추이 확인
- ES bulk 성공률 변화 관찰
- concurrency vs 파티션 수 비율 확인

**해결:**
- `consumer.concurrency` 3→6, `consumer.bulk-size` 500→1000
- stress 프로파일 적용 후 동일 테스트 재실행
- **결과:** _(테스트 후 기록)_

### Story 2: 추천 API P99 스파이크

**증상:** recommendation-load 200 VU에서 P99 > 1000ms

**원인 분석:**
- cold start 유저 비율과 KNN fallback 빈도 확인
- Redis 캐시 히트율 관찰 (popular 엔드포인트)
- ES KNN 검색 시간 분석

**해결:**
- `popular-ttl-minutes` 10→30, `global-cache-size` 100→200
- stress 프로파일 적용 후 재실행
- **결과:** _(테스트 후 기록)_

### Story 3: Redis OOM

**증상:** Redis `maxmemory`(512MB) 도달, `OOM command not allowed` 에러

**원인 분석:**
- `redis_memory_used_bytes` 추이 확인
- 선호 벡터 캐시(768 dims * float32 * 유저 수) 메모리 추정
- TTL 만료 전 메모리 포화 여부

**해결:**
- `docker-compose.yml`에서 `--maxmemory 1gb`로 증가
- 선호 벡터 TTL 24h→12h로 단축 검토
- **결과:** _(테스트 후 기록)_

### Story 4: ES Bulk 실패

**증상:** `es_bulk_failed_total` 증가, HTTP 429 (Too Many Requests)

**원인 분석:**
- ES Thread Pool rejection 확인
- bulk-size 1000에서 단일 요청 크기 추정
- `refresh_interval` 기본값(1s) 부담 확인

**해결:**
- ES `refresh_interval` 1s→30s로 변경 (부하 테스트 중)
- bulk-size 재조정 (1000→500)
- **결과:** _(테스트 후 기록)_

## 결과 비교 템플릿

| 메트릭 | 기본 프로파일 | stress 프로파일 | 변화율 |
|--------|-------------|----------------|--------|
| Consumer Lag (2000유저) | | | |
| Rec API P95 (200 VU) | | | |
| Rec API P99 (200 VU) | | | |
| ES Bulk 실패율 | | | |
| Notification 처리량/s | | | |
| Redis 메모리 피크 | | | |
