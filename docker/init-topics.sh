#!/bin/bash

echo "Waiting for Kafka to be ready..."
sleep 5

KAFKA_CONTAINER="rep-kafka"

echo "Creating Kafka topics..."

# user.action.v1 - 유저 행동 이벤트 (12 파티션)
docker exec $KAFKA_CONTAINER kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic user.action.v1 \
  --partitions 12 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  --config cleanup.policy=delete \
  --if-not-exists

# user.action.v1.dlq - Dead Letter Queue
docker exec $KAFKA_CONTAINER kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic user.action.v1.dlq \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=2592000000 \
  --if-not-exists

# product.inventory.v1 - 재고/가격 변동 (3 파티션)
docker exec $KAFKA_CONTAINER kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic product.inventory.v1 \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  --if-not-exists

# notification.push.v1 - 알림 발송 (6 파티션)
docker exec $KAFKA_CONTAINER kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic notification.push.v1 \
  --partitions 6 \
  --replication-factor 1 \
  --config retention.ms=86400000 \
  --if-not-exists

# product.inventory.v1.dlq - Dead Letter Queue
docker exec $KAFKA_CONTAINER kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic product.inventory.v1.dlq \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=2592000000 \
  --if-not-exists

# notification.push.v1.dlq - Dead Letter Queue
docker exec $KAFKA_CONTAINER kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic notification.push.v1.dlq \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=2592000000 \
  --if-not-exists

echo ""
echo "Topics created successfully!"
docker exec $KAFKA_CONTAINER kafka-topics --list --bootstrap-server localhost:9092
