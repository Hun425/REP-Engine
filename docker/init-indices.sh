#!/bin/bash

ES_HOST="http://localhost:9200"

echo "Waiting for Elasticsearch to be ready..."
until curl -s "$ES_HOST/_cluster/health" | grep -q '"status":"green"\|"status":"yellow"'; do
  echo "Elasticsearch is not ready yet..."
  sleep 5
done

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
      "vector": {
        "type": "dense_vector",
        "dims": 384,
        "index": false
      },
      "actionCount": { "type": "integer" },
      "updatedAt": { "type": "date" }
    }
  }
}'

echo ""

# notification_history_index - 알림 발송 이력
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
