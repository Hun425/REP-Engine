# Infrastructure: 인프라 구성 및 환경 설정

본 문서는 REP-Engine의 로컬 개발 환경 및 인프라 구성을 정의합니다.

## 1. 전체 인프라 구성도

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Docker Network (rep-network)                    │
│                                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────┐               │
│  │   Zookeeper  │───▶│    Kafka     │◀───│ Schema Registry  │               │
│  │   :2181      │    │   :9092      │    │     :8081        │               │
│  └──────────────┘    └──────┬───────┘    └──────────────────┘               │
│                             │                                                │
│                             ▼                                                │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌────────────┐ │
│  │    Kibana    │───▶│Elasticsearch │    │    Redis     │    │  Embedding │ │
│  │    :5601     │    │   :9200      │    │    :6379     │    │  Service   │ │
│  └──────────────┘    └──────────────┘    └──────────────┘    │   :8000    │ │
│                                                               └────────────┘ │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                    Observability Stack (Phase 5)                      │   │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────────┐  │   │
│  │  │ Prometheus │─▶│  Grafana   │◀─│    Loki    │◀─│   Promtail    │  │   │
│  │  │   :9090    │  │   :3000    │  │   :3100    │  │  (log agent)  │  │   │
│  │  └────────────┘  └─────┬──────┘  └────────────┘  └────────────────┘  │   │
│  │                        │                                              │   │
│  │                        ▼                                              │   │
│  │                  ┌────────────┐                                       │   │
│  │                  │   Jaeger   │  (Distributed Tracing)                │   │
│  │                  │  :16686    │                                       │   │
│  │                  └────────────┘                                       │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌──────────────┐                                                           │
│  │   Frontend   │                                                           │
│  │   :3001      │                                                           │
│  └──────────────┘                                                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Observability Stack 포트 정리

| 서비스 | 포트 | 용도 |
|--------|------|------|
| Prometheus | 9090 | 메트릭 수집/저장 |
| Grafana | 3000 | 대시보드 (메트릭, 로그, 트레이스 통합) |
| Loki | 3100 | 로그 집계 |
| Jaeger UI | 16686 | 분산 추적 UI |
| Jaeger OTLP gRPC | 4317 | 애플리케이션 → Jaeger 트레이스 전송 |
| Jaeger OTLP HTTP | 4318 | 애플리케이션 → Jaeger 트레이스 전송 |

### Spring Boot 애플리케이션 포트

> **참고**: 아래 포트는 `docker-compose.yml`에 정의되지 않습니다.
> Spring Boot 애플리케이션은 호스트에서 직접 실행하거나, 별도 컨테이너로 배포해야 합니다.

| 서비스 | 로컬 포트 | Docker 프로파일 포트 | 용도 |
|--------|----------|---------------------|------|
| Simulator | 8084 | 8084 | 트래픽 시뮬레이터 API |
| Behavior Consumer | 8085 | 8085 | Actuator 메트릭 (HTTP 요청 없음) |
| Recommendation API | 8080 | 8082 | 추천 API |
| Notification Service | 8083 | 8083 | Actuator 메트릭 |
| Frontend | 3001 | 3001 | React 대시보드 |

### Kafka 포트 구성

| 포트 | 리스너 | 용도 |
|------|--------|------|
| 9092 | PLAINTEXT_HOST | 호스트(로컬)에서 접근용. `localhost:9092`로 연결 |
| 29092 | PLAINTEXT | Docker 컨테이너 간 통신용. `kafka:29092`로 연결 |

> **참고**: Spring Boot 애플리케이션이 Docker 컨테이너로 실행될 때는 `kafka:29092`, 호스트에서 직접 실행될 때는 `localhost:9092`를 사용합니다.

## 2. Docker Compose 설정

### 2.1 docker-compose.yml

```yaml
version: '3.8'

services:
  # ============================================
  # Kafka Ecosystem
  # ============================================
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    hostname: zookeeper
    container_name: rep-zookeeper
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    volumes:
      - zookeeper-data:/var/lib/zookeeper/data
      - zookeeper-logs:/var/lib/zookeeper/log
    healthcheck:
      test: ["CMD", "nc", "-z", "localhost", "2181"]
      interval: 10s
      timeout: 5s
      retries: 5

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    hostname: kafka
    container_name: rep-kafka
    depends_on:
      zookeeper:
        condition: service_healthy
    ports:
      - "9092:9092"
      - "29092:29092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
      KAFKA_NUM_PARTITIONS: 12
    volumes:
      - kafka-data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD", "kafka-broker-api-versions", "--bootstrap-server", "localhost:9092"]
      interval: 10s
      timeout: 10s
      retries: 5

  schema-registry:
    image: confluentinc/cp-schema-registry:7.5.0
    hostname: schema-registry
    container_name: rep-schema-registry
    depends_on:
      kafka:
        condition: service_healthy
    ports:
      - "8081:8081"
    environment:
      SCHEMA_REGISTRY_HOST_NAME: schema-registry
      SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: kafka:29092
      SCHEMA_REGISTRY_LISTENERS: http://0.0.0.0:8081
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/subjects"]
      interval: 10s
      timeout: 5s
      retries: 5

  # ============================================
  # Elasticsearch Ecosystem
  # ============================================
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.11.0
    container_name: rep-elasticsearch
    environment:
      - node.name=es01
      - cluster.name=rep-cluster
      - discovery.type=single-node
      - bootstrap.memory_lock=true
      - xpack.security.enabled=false
      - xpack.security.enrollment.enabled=false
      - "ES_JAVA_OPTS=-Xms2g -Xmx2g"
    ulimits:
      memlock:
        soft: -1
        hard: -1
    volumes:
      - elasticsearch-data:/usr/share/elasticsearch/data
    ports:
      - "9200:9200"
    healthcheck:
      test: ["CMD-SHELL", "curl -s http://localhost:9200/_cluster/health | grep -q '\"status\":\"green\"\\|\"status\":\"yellow\"'"]
      interval: 10s
      timeout: 10s
      retries: 10

  kibana:
    image: docker.elastic.co/kibana/kibana:8.11.0
    container_name: rep-kibana
    depends_on:
      elasticsearch:
        condition: service_healthy
    ports:
      - "5601:5601"
    environment:
      ELASTICSEARCH_HOSTS: '["http://elasticsearch:9200"]'

  # ============================================
  # Cache Layer
  # ============================================
  # TTL 설정: Redis 서버 레벨이 아닌 애플리케이션 코드에서 키별 TTL 설정
  # - UserPreferenceRepository: 24시간 TTL (Duration.ofHours(24))
  # - PopularProductsCache: 10분 TTL (Duration.ofMinutes(10))
  # maxmemory-policy: volatile-lru - TTL이 설정된 키 중 LRU 방식으로 제거
  redis:
    image: redis:7.2-alpine
    container_name: rep-redis
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes --maxmemory 512mb --maxmemory-policy volatile-lru
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  # ============================================
  # Embedding Service (Phase 3)
  # ADR-003: multilingual-e5-base Self-hosted
  # ============================================
  embedding-service:
    build:
      context: ./embedding-service
      dockerfile: Dockerfile
    container_name: rep-embedding
    ports:
      - "8000:8000"
    environment:
      - MODEL_NAME=intfloat/multilingual-e5-base
      - WORKERS=1
    volumes:
      - embedding-model-cache:/root/.cache/huggingface
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8000/health"]
      interval: 30s
      timeout: 10s
      start_period: 120s
      retries: 3
    deploy:
      resources:
        limits:
          memory: 4G
        reservations:
          memory: 2G

  # ============================================
  # Observability Stack (Phase 5)
  # ============================================
  prometheus:
    image: prom/prometheus:v2.47.0
    container_name: rep-prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./prometheus/rules:/etc/prometheus/rules:ro
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--web.enable-lifecycle'
      - '--storage.tsdb.retention.time=15d'
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:9090/-/healthy"]
      interval: 10s
      timeout: 5s
      retries: 5

  grafana:
    image: grafana/grafana:10.2.0
    container_name: rep-grafana
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_USERS_ALLOW_SIGN_UP=false
      - GF_AUTH_ANONYMOUS_ENABLED=true
      - GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer
      - GF_SECURITY_ALLOW_EMBEDDING=true
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/grafana.ini:/etc/grafana/grafana.ini:ro
      - grafana-data:/var/lib/grafana
    depends_on:
      prometheus:
        condition: service_healthy
      loki:
        condition: service_healthy
      jaeger:
        condition: service_healthy

  # ============================================
  # Logging Stack (Loki + Promtail)
  # ============================================
  loki:
    image: grafana/loki:3.0.0
    container_name: rep-loki
    ports:
      - "3100:3100"
    volumes:
      - ./loki/loki-config.yaml:/etc/loki/local-config.yaml:ro
      - loki-data:/loki
    command: -config.file=/etc/loki/local-config.yaml
    healthcheck:
      test: ["CMD-SHELL", "wget --no-verbose --tries=1 --spider http://localhost:3100/ready || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5

  promtail:
    image: grafana/promtail:3.0.0
    container_name: rep-promtail
    volumes:
      - ./promtail/promtail-config.yaml:/etc/promtail/config.yaml:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
    command: -config.file=/etc/promtail/config.yaml
    depends_on:
      loki:
        condition: service_healthy

  # ============================================
  # Distributed Tracing (Phase 5)
  # ============================================
  jaeger:
    image: jaegertracing/all-in-one:1.62.0
    container_name: rep-jaeger
    ports:
      - "16686:16686"  # Jaeger UI
      - "4317:4317"    # OTLP gRPC
      - "4318:4318"    # OTLP HTTP
    environment:
      - COLLECTOR_OTLP_ENABLED=true
    healthcheck:
      test: ["CMD-SHELL", "wget --no-verbose --tries=1 --spider http://localhost:16686/ || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5

  # ============================================
  # Infrastructure Exporters
  # ============================================
  kafka-exporter:
    image: danielqsj/kafka-exporter:latest
    container_name: rep-kafka-exporter
    ports:
      - "9308:9308"
    command:
      - '--kafka.server=kafka:29092'
      - '--log.level=warn'
    depends_on:
      kafka:
        condition: service_healthy

  elasticsearch-exporter:
    image: quay.io/prometheuscommunity/elasticsearch-exporter:v1.6.0
    container_name: rep-es-exporter
    ports:
      - "9114:9114"
    command:
      - '--es.uri=http://elasticsearch:9200'
      - '--es.all'
      - '--es.indices'
    depends_on:
      elasticsearch:
        condition: service_healthy

  redis-exporter:
    image: oliver006/redis_exporter:latest
    container_name: rep-redis-exporter
    ports:
      - "9121:9121"
    environment:
      - REDIS_ADDR=redis://redis:6379
    depends_on:
      redis:
        condition: service_healthy

  # ============================================
  # Frontend (Phase 6)
  # ============================================
  frontend:
    build:
      context: ../frontend
      dockerfile: Dockerfile
    container_name: rep-frontend
    ports:
      - "3001:80"
    depends_on:
      - grafana
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:80/health"]
      interval: 30s
      timeout: 10s
      retries: 3

volumes:
  zookeeper-data:
  zookeeper-logs:
  kafka-data:
  elasticsearch-data:
  redis-data:
  embedding-model-cache:
  prometheus-data:
  grafana-data:
  loki-data:

networks:
  default:
    name: rep-network
```

## 3. Kafka 토픽 초기화 스크립트

### 3.1 init-topics.sh

```bash
#!/bin/bash

KAFKA_BOOTSTRAP_SERVER="localhost:9092"

echo "Creating Kafka topics..."

# user.action.v1 - 유저 행동 이벤트
# 파티션 12개: Consumer 인스턴스 최대 12개까지 병렬 처리 가능
# 예상 TPS 10,000 기준, 파티션당 ~833 TPS 처리
kafka-topics --create \
  --bootstrap-server $KAFKA_BOOTSTRAP_SERVER \
  --topic user.action.v1 \
  --partitions 12 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  --config cleanup.policy=delete \
  --if-not-exists

# user.action.v1.dlq - Dead Letter Queue (behavior-consumer)
kafka-topics --create \
  --bootstrap-server $KAFKA_BOOTSTRAP_SERVER \
  --topic user.action.v1.dlq \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=2592000000 \
  --if-not-exists

# product.inventory.v1 - 재고/가격 변동 이벤트
# 파티션 3개: 상품 변동은 유저 행동보다 빈도 낮음
kafka-topics --create \
  --bootstrap-server $KAFKA_BOOTSTRAP_SERVER \
  --topic product.inventory.v1 \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  --if-not-exists

# notification.push.v1 - 알림 발송 큐
# 파티션 6개: 알림 발송 Worker 수에 맞춤
kafka-topics --create \
  --bootstrap-server $KAFKA_BOOTSTRAP_SERVER \
  --topic notification.push.v1 \
  --partitions 6 \
  --replication-factor 1 \
  --config retention.ms=86400000 \
  --if-not-exists

# product.inventory.v1.dlq - Dead Letter Queue (notification-service)
kafka-topics --create \
  --bootstrap-server $KAFKA_BOOTSTRAP_SERVER \
  --topic product.inventory.v1.dlq \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=2592000000 \
  --if-not-exists

# notification.push.v1.dlq - Dead Letter Queue (push-sender)
kafka-topics --create \
  --bootstrap-server $KAFKA_BOOTSTRAP_SERVER \
  --topic notification.push.v1.dlq \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=2592000000 \
  --if-not-exists

echo "Topics created successfully!"
kafka-topics --list --bootstrap-server $KAFKA_BOOTSTRAP_SERVER
```

## 4. Elasticsearch 인덱스 초기화

### 4.1 init-indices.sh

```bash
#!/bin/bash

ES_HOST="http://localhost:9200"

echo "Creating Elasticsearch indices..."

# user_behavior_index - 유저 행동 로그
curl -X PUT "$ES_HOST/user_behavior_index" -H 'Content-Type: application/json' -d '
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 0,
    "refresh_interval": "5s",
    "index.mapping.total_fields.limit": 100
  },
  "mappings": {
    "properties": {
      "traceId": { "type": "keyword" },
      "userId": { "type": "keyword" },
      "productId": { "type": "keyword" },
      "category": { "type": "keyword" },
      "actionType": { "type": "keyword" },
      "metadata": { "type": "object", "enabled": false },
      "timestamp": { "type": "date" }
    }
  }
}'

echo ""

# product_index - 상품 정보 + 벡터
# Note: ES 8.x에서는 dense_vector가 자동으로 KNN 검색 지원 (index.knn 설정 불필요)
curl -X PUT "$ES_HOST/product_index" -H 'Content-Type: application/json' -d '
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "productId": { "type": "keyword" },
      "productName": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "keyword": { "type": "keyword" }
        }
      },
      "category": { "type": "keyword" },
      "subCategory": { "type": "keyword" },
      "price": { "type": "float" },
      "stock": { "type": "integer" },
      "brand": { "type": "keyword" },
      "description": { "type": "text" },
      "tags": { "type": "keyword" },
      "productVector": {
        "type": "dense_vector",
        "dims": 768,
        "index": true,
        "similarity": "cosine",
        "index_options": {
          "type": "hnsw",
          "m": 16,
          "ef_construction": 100
        }
      },
      "createdAt": { "type": "date" },
      "updatedAt": { "type": "date" }
    }
  }
}'

echo ""

# user_preference_index - 유저 취향 벡터 (백업용, KNN 검색 대상 아님)
# ADR-004: 유저 벡터는 Redis에서 조회, ES는 백업 용도이므로 KNN 인덱스 불필요
curl -X PUT "$ES_HOST/user_preference_index" -H 'Content-Type: application/json' -d '
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "refresh_interval": "60s"
  },
  "mappings": {
    "properties": {
      "userId": { "type": "keyword" },
      "preferenceVector": {
        "type": "dense_vector",
        "dims": 768,
        "index": false
      },
      "actionCount": { "type": "integer" },
      "updatedAt": { "type": "date" }
    }
  }
}'

echo ""

# notification_history_index - 알림 발송 이력 (Phase 4)
curl -X PUT "$ES_HOST/notification_history_index" -H 'Content-Type: application/json' -d '
{
  "settings": {
    "number_of_shards": 2,
    "number_of_replicas": 0,
    "refresh_interval": "5s"
  },
  "mappings": {
    "properties": {
      "notificationId": { "type": "keyword" },
      "userId": { "type": "keyword" },
      "productId": { "type": "keyword" },
      "type": { "type": "keyword" },
      "title": { "type": "text" },
      "body": { "type": "text" },
      "channels": { "type": "keyword" },
      "status": { "type": "keyword" },
      "sentAt": { "type": "date" }
    }
  }
}'

echo ""
echo "Indices created successfully!"
curl -s "$ES_HOST/_cat/indices?v"
```

## 5. 환경별 설정

각 모듈은 고유한 `application.yml` 파일을 가지고 있습니다. 모듈별 설정은 해당 모듈의 `src/main/resources/application.yml`을 참고하세요:

| 모듈 | 설정 파일 경로 |
|------|---------------|
| simulator | `simulator/src/main/resources/application.yml` |
| behavior-consumer | `behavior-consumer/src/main/resources/application.yml` |
| recommendation-api | `recommendation-api/src/main/resources/application.yml` |
| notification-service | `notification-service/src/main/resources/application.yml` |

> **공통 패턴**: 모든 모듈은 로컬 환경(기본)과 Docker 환경(`docker` 프로필)을 지원합니다.
> Docker 프로필 활성화: `--spring.profiles.active=docker`

## 6. 실행 가이드

### 6.1 전체 인프라 시작

```bash
# 인프라 시작
docker-compose up -d

# 헬스체크 대기 (약 30초 소요)
docker-compose ps

# Kafka 토픽 생성 (필수! AUTO_CREATE_TOPICS=false이므로 수동 생성 필요)
# 방법 1: Docker 컨테이너 내부에서 실행 (권장)
docker exec rep-kafka bash -c '
  kafka-topics --create --bootstrap-server localhost:9092 --topic user.action.v1 --partitions 12 --replication-factor 1 --if-not-exists &&
  kafka-topics --create --bootstrap-server localhost:9092 --topic user.action.v1.dlq --partitions 3 --replication-factor 1 --if-not-exists &&
  kafka-topics --create --bootstrap-server localhost:9092 --topic product.inventory.v1 --partitions 3 --replication-factor 1 --if-not-exists &&
  kafka-topics --create --bootstrap-server localhost:9092 --topic product.inventory.v1.dlq --partitions 3 --replication-factor 1 --if-not-exists &&
  kafka-topics --create --bootstrap-server localhost:9092 --topic notification.push.v1 --partitions 6 --replication-factor 1 --if-not-exists &&
  kafka-topics --create --bootstrap-server localhost:9092 --topic notification.push.v1.dlq --partitions 3 --replication-factor 1 --if-not-exists
'

# 방법 2: 호스트에서 스크립트 실행 (Kafka CLI 설치 필요)
# ./init-topics.sh

# Elasticsearch 인덱스 생성
./init-indices.sh

# (선택) 테스트용 상품 데이터 시딩
python seed_products.py 700  # 카테고리당 약 100개씩 생성
```

> **중요:** `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`로 설정되어 있으므로,
> `init-topics.sh`를 실행하지 않으면 메시지 발행 시 에러가 발생합니다.

### 6.2 상태 확인

```bash
# Kafka 토픽 확인
docker exec rep-kafka kafka-topics --list --bootstrap-server localhost:9092

# Elasticsearch 클러스터 상태
curl http://localhost:9200/_cluster/health?pretty

# Redis 연결 확인
docker exec rep-redis redis-cli ping

# Schema Registry 확인
curl http://localhost:8081/subjects
```

### 6.3 종료 및 정리

```bash
# 컨테이너 중지
docker-compose down

# 볼륨까지 삭제 (데이터 초기화)
docker-compose down -v
```

## 7. 보안 고려사항 (개발 환경)

> ⚠️ **경고**: 아래 설정은 **로컬 개발 환경 전용**입니다. 프로덕션 환경에서는 반드시 변경해야 합니다.

### 7.1 Grafana 익명 접근

현재 설정 (`docker-compose.yml`):
```yaml
grafana:
  environment:
    - GF_AUTH_ANONYMOUS_ENABLED=true
    - GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer
    - GF_SECURITY_ALLOW_EMBEDDING=true
```

**프로덕션 권장 설정:**
```yaml
grafana:
  environment:
    - GF_AUTH_ANONYMOUS_ENABLED=false
    - GF_SECURITY_ALLOW_EMBEDDING=false
    - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD}  # 환경 변수로 관리
```

### 7.2 Elasticsearch 보안

현재 설정:
```yaml
elasticsearch:
  environment:
    - xpack.security.enabled=false
```

**프로덕션 권장 설정:**
- `xpack.security.enabled=true`
- TLS/SSL 인증서 설정
- 인증/인가 정책 구성

### 7.3 Redis 인증

현재 설정: 인증 없음

**프로덕션 권장 설정:**
```yaml
redis:
  command: redis-server --appendonly yes --requirepass ${REDIS_PASSWORD}
```

### 7.4 Kafka 보안

현재 설정: PLAINTEXT 리스너 (암호화 없음)

**프로덕션 권장 설정:**
- SASL_SSL 리스너 구성
- ACL 기반 토픽 접근 제어

## 8. 분산 추적 (Jaeger OTLP)

### 8.1 아키텍처

```
┌───────────────────┐     OTLP HTTP (4318)     ┌──────────────┐
│   Application     │───────────────────────────▶│    Jaeger    │
│  (Spring Boot)    │                            │  All-in-One  │
│                   │                            │              │
│ - simulator       │                            │  Storage:    │
│ - behavior-consumer│                           │  In-memory   │
│ - recommendation-api│                          │              │
│ - notification-service│                        │  UI: 16686   │
└───────────────────┘                            └──────────────┘
```

### 8.2 애플리케이션 설정

모든 Spring Boot 모듈에서 Docker 프로필 활성화 시 자동 적용:

```yaml
# application.yml (docker profile)
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0  # 100% 샘플링 (개발용)
  otlp:
    tracing:
      endpoint: http://jaeger:4318/v1/traces
```

| 모듈 | 로컬 환경 | Docker 환경 |
|------|----------|-------------|
| simulator | 비활성화 | ✅ OTLP HTTP |
| behavior-consumer | 비활성화 | ✅ OTLP HTTP |
| recommendation-api | 비활성화 | ✅ OTLP HTTP |
| notification-service | 비활성화 | ✅ OTLP HTTP |

### 8.3 Grafana 연동

Loki → Jaeger 자동 연결 설정 (`datasource.yml`):
```yaml
- name: Loki
  jsonData:
    derivedFields:
      - datasourceUid: jaeger
        matcherRegex: '"traceId":"([a-f0-9]+)"'
        name: TraceID
        url: '$${__value.raw}'
```

로그에서 `traceId` 클릭 시 Jaeger UI로 자동 이동하여 전체 요청 흐름 추적 가능.

## 9. 설정 파일 목록

Docker 디렉토리 내 주요 설정 파일들입니다.

| 파일 | 위치 | 용도 |
|------|------|------|
| `loki-config.yaml` | `docker/loki/` | Loki 로그 저장/보존 설정. 기본 7일(168h) 보관 |
| `promtail-config.yaml` | `docker/promtail/` | 로그 수집 설정. Docker 컨테이너 로그 수집 및 Loki 전송 |
| `grafana.ini` | `docker/grafana/` | Grafana 서버 설정. 포트, 익명 접근, 임베딩 설정 |
| `alert_rules.yml` | `docker/prometheus/rules/` | Prometheus 알림 규칙. 인프라/애플리케이션 알림 정의 |
| `prometheus.yml` | `docker/prometheus/` | Prometheus 스크레이프 설정. 메트릭 수집 대상 정의 |
| `datasource.yml` | `docker/grafana/provisioning/datasources/` | Grafana 데이터소스 자동 프로비저닝 |

### 데이터 보존 기간

| 서비스 | 보존 기간 | 설정 위치 |
|--------|----------|----------|
| Loki | 7일 (168h) | `loki-config.yaml` → `retention_period` |
| Prometheus | 15일 | `docker-compose.yml` → `--storage.tsdb.retention.time=15d` |
| Jaeger | 메모리 (휘발성) | all-in-one 이미지 기본 설정 |

> **프로덕션 참고**: Loki/Prometheus 보존 기간은 개발 환경 기준입니다. 프로덕션에서는 요구사항에 맞게 조정하고, Jaeger는 영구 저장소(Cassandra, Elasticsearch 등) 설정이 필요합니다.

## 10. 리소스 요구사항

| 컴포넌트 | CPU | Memory | Disk |
|---------|-----|--------|------|
| Zookeeper | 0.5 core | 512MB | 1GB |
| Kafka | 1 core | 1GB | 10GB |
| Schema Registry | 0.5 core | 512MB | - |
| Elasticsearch | 2 cores | 4GB | 20GB |
| Kibana | 0.5 core | 512MB | - |
| Redis | 0.5 core | 512MB | 1GB |
| Embedding Service | 1 core | 2-4GB | 2GB (모델 캐시) |
| Prometheus | 0.5 core | 512MB | 5GB |
| Grafana | 0.5 core | 256MB | - |
| Loki | 0.5 core | 512MB | 5GB |
| Promtail | 0.2 core | 128MB | - |
| Jaeger | 0.5 core | 512MB | 1GB |
| Frontend | 0.5 core | 128MB | - |
| **합계** | **8.7 cores** | **11-13GB** | **45GB** |

> 로컬 개발 환경 기준이며, 프로덕션 환경에서는 각 컴포넌트의 클러스터링 및 리소스 확장이 필요합니다.

## 11. 관련 문서

- [마스터 설계서](./마스터%20설계서.md)
- [ADR-001: 동시성 전략](./adr-001-concurrency-strategy.md)
- [ADR-002: Schema Registry](./adr-002-schema-registry.md)
- [ADR-003: Embedding 모델](./adr-003-embedding-model.md)
- [ADR-004: 벡터 저장소](./adr-004-vector-storage.md)
- [Phase 6: 프론트엔드](./phase%206.md)