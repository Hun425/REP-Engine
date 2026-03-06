# 로컬 16GB 개발 최적화 가이드

이 문서는 `MacBook Air 16GB` 같은 제한된 메모리 환경에서 REP-Engine을 안정적으로 실행하기 위한 운영 가이드입니다.

## 목표

1. 필수 인프라만 올려서 스왑을 줄인다.
2. 모니터링 스택은 필요할 때만 켠다.
3. 동일 명령으로 재현 가능한 실행 절차를 만든다.

## 기본 원칙

1. 항상 `필수 서비스 우선`으로 시작한다.
2. `관측/대시보드`는 디버깅 시에만 임시로 켠다.
3. Elasticsearch 힙은 16GB 환경에서 낮춰서 운영한다.

## 1) 저메모리 모드 파일

저장소에 아래 오버라이드 파일이 추가되어 있습니다.

- `/Users/hun/Desktop/etc/REP-engine/docker/docker-compose.lowmem.yml`

적용 내용:
1. Kafka heap: `-Xms512m -Xmx512m`
2. Elasticsearch heap: `-Xms1g -Xmx1g`
3. Redis maxmemory: `256mb`

## 2) 권장 실행 시나리오

### A. 기본 개발(권장)

모니터링 제외, 핵심 인프라만 실행:

```bash
docker compose \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.lowmem.yml \
  up -d zookeeper kafka schema-registry elasticsearch redis
```

추천 API/소비자까지 함께 실행이 필요하면:

```bash
docker compose \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.lowmem.yml \
  up -d zookeeper kafka schema-registry elasticsearch redis embedding-service
```

### B. 트레이싱만 잠깐 확인할 때

```bash
docker compose -f docker/docker-compose.yml up -d jaeger
```

확인 후 바로 내리기:

```bash
docker compose -f docker/docker-compose.yml stop jaeger
```

### C. 모니터링 스택이 정말 필요할 때만

```bash
docker compose -f docker/docker-compose.yml up -d \
  prometheus grafana loki promtail \
  kafka-exporter elasticsearch-exporter redis-exporter
```

작업이 끝나면 즉시 중지:

```bash
docker compose -f docker/docker-compose.yml stop \
  prometheus grafana loki promtail \
  kafka-exporter elasticsearch-exporter redis-exporter
```

## 3) 모듈별 최소 인프라 매핑

1. simulator: Kafka, Schema Registry
2. behavior-consumer: Kafka, Schema Registry, Elasticsearch, Redis, embedding-service
3. recommendation-api: Elasticsearch, Redis
4. notification-service: Kafka, Elasticsearch, Redis

필요한 모듈만 실행하면 메모리를 크게 절약할 수 있습니다.

## 4) 점검 명령

현재 컨테이너 메모리 사용량 확인:

```bash
docker stats --no-stream
```

서비스 상태 확인:

```bash
docker compose -f docker/docker-compose.yml ps
```

## 5) 종료/정리

컨테이너만 중지:

```bash
docker compose -f docker/docker-compose.yml stop
```

컨테이너와 네트워크 정리:

```bash
docker compose -f docker/docker-compose.yml down
```

볼륨까지 삭제(데이터 초기화, 필요 시에만):

```bash
docker compose -f docker/docker-compose.yml down -v
```

## 6) 16GB 환경 운영 팁

1. IntelliJ 프로젝트는 필요한 모듈만 열기
2. 크롬 탭 수 최소화
3. `ktlintCheck`/`test`는 동시에 돌리지 말고 순차 실행
4. 스왑이 심해지면 모니터링 스택부터 내리기

