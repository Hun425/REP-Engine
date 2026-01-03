@echo off
echo Waiting for Kafka to be ready...
timeout /t 5 /nobreak > nul

set KAFKA_CONTAINER=rep-kafka

echo Creating Kafka topics...

docker exec %KAFKA_CONTAINER% kafka-topics --create --bootstrap-server localhost:9092 --topic user.action.v1 --partitions 12 --replication-factor 1 --config retention.ms=604800000 --config cleanup.policy=delete --if-not-exists

docker exec %KAFKA_CONTAINER% kafka-topics --create --bootstrap-server localhost:9092 --topic user.action.v1.dlq --partitions 3 --replication-factor 1 --config retention.ms=2592000000 --if-not-exists

docker exec %KAFKA_CONTAINER% kafka-topics --create --bootstrap-server localhost:9092 --topic product.inventory.v1 --partitions 3 --replication-factor 1 --config retention.ms=604800000 --if-not-exists

docker exec %KAFKA_CONTAINER% kafka-topics --create --bootstrap-server localhost:9092 --topic notification.push.v1 --partitions 6 --replication-factor 1 --config retention.ms=86400000 --if-not-exists

echo.
echo Topics created successfully!
docker exec %KAFKA_CONTAINER% kafka-topics --list --bootstrap-server localhost:9092
