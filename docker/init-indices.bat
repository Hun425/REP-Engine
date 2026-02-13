@echo off
set ES_HOST=http://localhost:9200
set VECTOR_DIMS=768

echo Waiting for Elasticsearch to be ready...
:wait_es
curl -s "%ES_HOST%/_cluster/health" | findstr /C:"green" /C:"yellow" > nul
if errorlevel 1 (
    echo Elasticsearch is not ready yet...
    timeout /t 5 /nobreak > nul
    goto wait_es
)

echo Creating Elasticsearch indices...

curl -X PUT "%ES_HOST%/user_behavior_index" -H "Content-Type: application/json" -d "{\"settings\":{\"number_of_shards\":3,\"number_of_replicas\":0,\"refresh_interval\":\"5s\",\"index.mapping.total_fields.limit\":100},\"mappings\":{\"properties\":{\"traceId\":{\"type\":\"keyword\"},\"userId\":{\"type\":\"keyword\"},\"productId\":{\"type\":\"keyword\"},\"category\":{\"type\":\"keyword\"},\"actionType\":{\"type\":\"keyword\"},\"metadata\":{\"type\":\"object\",\"enabled\":false},\"timestamp\":{\"type\":\"date\"}}}}"

echo.

REM product_index - ES 8.x에서는 dense_vector가 자동으로 KNN 검색 지원
curl -X PUT "%ES_HOST%/product_index" -H "Content-Type: application/json" -d "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"productId\":{\"type\":\"keyword\"},\"productName\":{\"type\":\"text\",\"analyzer\":\"standard\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"category\":{\"type\":\"keyword\"},\"subCategory\":{\"type\":\"keyword\"},\"price\":{\"type\":\"float\"},\"stock\":{\"type\":\"integer\"},\"brand\":{\"type\":\"keyword\"},\"description\":{\"type\":\"text\"},\"tags\":{\"type\":\"keyword\"},\"productVector\":{\"type\":\"dense_vector\",\"dims\":%VECTOR_DIMS%,\"index\":true,\"similarity\":\"cosine\",\"index_options\":{\"type\":\"hnsw\",\"m\":16,\"ef_construction\":100}},\"createdAt\":{\"type\":\"date\"},\"updatedAt\":{\"type\":\"date\"}}}}"

echo.

REM user_preference_index - ADR-004: KNN 인덱스 불필요 (백업용)
curl -X PUT "%ES_HOST%/user_preference_index" -H "Content-Type: application/json" -d "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0,\"refresh_interval\":\"60s\"},\"mappings\":{\"properties\":{\"userId\":{\"type\":\"keyword\"},\"preferenceVector\":{\"type\":\"dense_vector\",\"dims\":%VECTOR_DIMS%,\"index\":false},\"actionCount\":{\"type\":\"integer\"},\"updatedAt\":{\"type\":\"date\"}}}}"

echo.

curl -X PUT "%ES_HOST%/notification_history_index" -H "Content-Type: application/json" -d "{\"settings\":{\"number_of_shards\":2,\"number_of_replicas\":0,\"refresh_interval\":\"5s\"},\"mappings\":{\"properties\":{\"notificationId\":{\"type\":\"keyword\"},\"userId\":{\"type\":\"keyword\"},\"productId\":{\"type\":\"keyword\"},\"type\":{\"type\":\"keyword\"},\"title\":{\"type\":\"text\"},\"body\":{\"type\":\"text\"},\"data\":{\"type\":\"object\",\"enabled\":false},\"channels\":{\"type\":\"keyword\"},\"priority\":{\"type\":\"keyword\"},\"status\":{\"type\":\"keyword\"},\"sentAt\":{\"type\":\"date\"}}}}"

echo.

curl -X PUT "%ES_HOST%/trace_anomaly_index" -H "Content-Type: application/json" -d "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0,\"refresh_interval\":\"5s\"},\"mappings\":{\"properties\":{\"traceId\":{\"type\":\"keyword\"},\"type\":{\"type\":\"keyword\"},\"severity\":{\"type\":\"keyword\"},\"serviceName\":{\"type\":\"keyword\"},\"operationName\":{\"type\":\"keyword\"},\"durationMs\":{\"type\":\"long\"},\"thresholdMs\":{\"type\":\"long\"},\"errorMessage\":{\"type\":\"text\"},\"spanCount\":{\"type\":\"integer\"},\"metadata\":{\"type\":\"object\",\"enabled\":false},\"note\":{\"type\":\"text\"},\"isBookmark\":{\"type\":\"boolean\"},\"detectedAt\":{\"type\":\"date\"},\"createdAt\":{\"type\":\"date\"}}}}"

echo.
echo Indices created successfully!
curl -s "%ES_HOST%/_cat/indices?v"
