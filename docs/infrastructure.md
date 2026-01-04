# Infrastructure: 인프라 구성 및 환경 설정

본 문서는 REP-Engine의 로컬 개발 환경 및 인프라 구성을 정의합니다.

## 1. 전체 인프라 구성도

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Docker Network                                   │
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
└─────────────────────────────────────────────────────────────────────────────┘
```

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
  redis:
    image: redis:7.2-alpine
    container_name: rep-redis
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes --maxmemory 512mb --maxmemory-policy allkeys-lru
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
  # Observability (Optional - Phase 5)
  # ============================================
  # prometheus:
  #   image: prom/prometheus:v2.47.0
  #   container_name: rep-prometheus
  #   ports:
  #     - "9090:9090"
  #   volumes:
  #     - ./prometheus.yml:/etc/prometheus/prometheus.yml

  # grafana:
  #   image: grafana/grafana:10.2.0
  #   container_name: rep-grafana
  #   ports:
  #     - "3000:3000"
  #   depends_on:
  #     - prometheus

volumes:
  zookeeper-data:
  zookeeper-logs:
  kafka-data:
  elasticsearch-data:
  redis-data:
  embedding-model-cache:

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

# user.action.v1.dlq - Dead Letter Queue
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
        "dims": 384,
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
        "dims": 384,
        "index": false
      },
      "actionCount": { "type": "integer" },
      "lastUpdated": { "type": "date" }
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

### 5.1 application.yml (Spring Boot)

```yaml
spring:
  application:
    name: rep-engine

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        linger.ms: 5
        batch.size: 16384
        schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
    consumer:
      group-id: behavior-consumer-group
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
      max-poll-records: 500
      properties:
        schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
        specific.avro.reader: true

  elasticsearch:
    uris: ${ELASTICSEARCH_URIS:http://localhost:9200}

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

# Embedding Service (Phase 3)
embedding:
  service:
    url: ${EMBEDDING_SERVICE_URL:http://localhost:8000}
    timeout: 5000
    batch-size: 32

---
spring:
  config:
    activate:
      on-profile: docker

  kafka:
    bootstrap-servers: kafka:29092
    producer:
      properties:
        schema.registry.url: http://schema-registry:8081
    consumer:
      properties:
        schema.registry.url: http://schema-registry:8081

  elasticsearch:
    uris: http://elasticsearch:9200

  data:
    redis:
      host: redis
      port: 6379
```

## 6. 실행 가이드

### 6.1 전체 인프라 시작

```bash
# 인프라 시작
docker-compose up -d

# 헬스체크 대기 (약 30초 소요)
docker-compose ps

# Kafka 토픽 생성 (필수! AUTO_CREATE_TOPICS=false이므로 수동 생성 필요)
./init-topics.sh

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

## 7. 리소스 요구사항

| 컴포넌트 | CPU | Memory | Disk |
|---------|-----|--------|------|
| Zookeeper | 0.5 core | 512MB | 1GB |
| Kafka | 1 core | 1GB | 10GB |
| Schema Registry | 0.5 core | 512MB | - |
| Elasticsearch | 2 cores | 4GB | 20GB |
| Kibana | 0.5 core | 512MB | - |
| Redis | 0.5 core | 512MB | 1GB |
| Embedding Service | 1 core | 2-4GB | 2GB (모델 캐시) |
| **합계** | **6 cores** | **9-11GB** | **34GB** |

> 로컬 개발 환경 기준이며, 프로덕션 환경에서는 각 컴포넌트의 클러스터링 및 리소스 확장이 필요합니다.

## 8. 관련 문서

- [마스터 설계서](./마스터%20설계서.md)
- [ADR-001: 동시성 전략](./adr-001-concurrency-strategy.md)
- [ADR-002: Schema Registry](./adr-002-schema-registry.md)
- [ADR-003: Embedding 모델](./adr-003-embedding-model.md)
- [ADR-004: 벡터 저장소](./adr-004-vector-storage.md)