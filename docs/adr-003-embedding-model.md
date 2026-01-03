# ADR-003: Embedding 모델 선택

## 상태 (Status)
**채택됨 (Accepted)**

## 배경 (Context)

REP-Engine의 추천 시스템은 Elasticsearch의 벡터 검색(KNN)을 활용합니다. 상품과 유저의 취향을 벡터로 표현해야 하며, 이를 위한 Embedding 모델 선택이 필요합니다.

### 요구사항

1. **상품 임베딩:** 상품명, 카테고리, 설명을 기반으로 의미적 유사도 표현
2. **실시간 처리:** 유저 행동 발생 시 취향 벡터 즉시 업데이트 가능해야 함
3. **비용 효율성:** 대량의 상품/유저에 대해 지속적으로 임베딩 생성
4. **다국어 지원:** 한국어 상품 데이터 처리 필수

## 검토한 대안 (Alternatives Considered)

### Option 1: OpenAI text-embedding-3-small

| 항목 | 내용 |
|-----|-----|
| 차원 | 1536 (또는 축소 가능) |
| 비용 | $0.00002 / 1K tokens |
| 장점 | 최고 수준 품질, 다국어 우수, API 호출만으로 간편 |
| 단점 | 외부 API 의존 (네트워크 지연, 장애 위험), 비용 누적, 데이터 외부 전송 |

### Option 2: OpenAI text-embedding-3-large

| 항목 | 내용 |
|-----|-----|
| 차원 | 3072 (또는 축소 가능) |
| 비용 | $0.00013 / 1K tokens |
| 장점 | 최고 품질 |
| 단점 | 비용 높음, 차원이 커서 ES 인덱스 크기 증가 |

### Option 3: Sentence-Transformers (self-hosted)

**multilingual-e5-base** 또는 **paraphrase-multilingual-MiniLM-L12-v2**

| 항목 | 내용 |
|-----|-----|
| 차원 | 384 ~ 768 |
| 비용 | 인프라 비용만 (GPU 서버) |
| 장점 | 외부 의존 없음, 데이터 외부 전송 없음, 커스터마이징 가능 |
| 단점 | GPU 인프라 필요, 초기 세팅 복잡, 모델 관리 책임 |

### Option 4: Cohere embed-multilingual-v3.0

| 항목 | 내용 |
|-----|-----|
| 차원 | 1024 |
| 비용 | $0.0001 / 1K tokens |
| 장점 | 다국어 특화, 검색 최적화 모드 지원 |
| 단점 | OpenAI보다 생태계 작음 |

### Option 5: Hybrid (Self-hosted + API Fallback)

평소에는 self-hosted 모델 사용, 고품질이 필요한 상품 마스터 데이터는 OpenAI API 활용

## 결정 (Decision)

**Sentence-Transformers의 `multilingual-e5-base` 모델을 Self-hosted로 운영**합니다.

추가로 상품 마스터 데이터 초기 임베딩 시에는 **OpenAI text-embedding-3-small**을 선택적으로 활용할 수 있도록 합니다.

### 선택 이유

1. **비용 예측 가능성:** 초당 수만 건 이벤트 처리 시 API 비용이 급격히 증가. Self-hosted는 고정 인프라 비용
2. **지연 시간:** 로컬 추론으로 네트워크 왕복 시간 제거 (< 10ms vs 100ms+)
3. **데이터 보안:** 유저 행동 데이터가 외부로 전송되지 않음
4. **모델 품질:** multilingual-e5-base는 MTEB 벤치마크에서 한국어 성능 상위권
5. **차원 효율:** 384 차원으로 ES 인덱스 크기와 KNN 검색 속도 최적화

## 상세 설계 (Implementation)

### 모델 스펙

| 항목 | 값 |
|-----|-----|
| 모델명 | intfloat/multilingual-e5-base |
| 차원 (dims) | 384 |
| 최대 토큰 | 512 |
| 모델 크기 | ~1.1GB |
| 추론 속도 (GPU) | ~5ms / request |
| 추론 속도 (CPU) | ~50ms / request |

### 임베딩 파이프라인 구조

```
┌──────────────────────────────────────────────────────────────┐
│                    Embedding Service                          │
│  ┌─────────────────┐     ┌─────────────────────────────────┐ │
│  │  REST API       │────▶│  multilingual-e5-base (GPU)     │ │
│  │  /embed         │     │  - Batch Processing             │ │
│  │  /embed/batch   │     │  - Connection Pooling           │ │
│  └─────────────────┘     └─────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────────────┐
│                      사용처                                   │
│  1. 상품 등록 시 → productVector 생성 → ES 인덱싱            │
│  2. 유저 행동 시 → 행동한 상품 벡터 조회 → 취향 벡터 갱신    │
│  3. 추천 요청 시 → 유저 취향 벡터로 KNN 검색                  │
└──────────────────────────────────────────────────────────────┘
```

### 임베딩 서비스 (Python FastAPI)

```python
from fastapi import FastAPI
from sentence_transformers import SentenceTransformer
from pydantic import BaseModel

app = FastAPI()
model = SentenceTransformer('intfloat/multilingual-e5-base')

class EmbedRequest(BaseModel):
    texts: list[str]
    prefix: str = "query: "  # e5 모델은 prefix 필요

@app.post("/embed")
async def embed(request: EmbedRequest):
    # e5 모델은 검색용 prefix 추가 권장
    prefixed_texts = [request.prefix + t for t in request.texts]
    embeddings = model.encode(prefixed_texts, normalize_embeddings=True)
    return {"embeddings": embeddings.tolist()}

@app.get("/health")
async def health():
    return {"status": "ok", "model": "multilingual-e5-base", "dims": 384}
```

### Docker 설정

```dockerfile
FROM python:3.11-slim

WORKDIR /app
RUN pip install fastapi uvicorn sentence-transformers torch

# 모델 미리 다운로드
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('intfloat/multilingual-e5-base')"

COPY embedding_service.py .
CMD ["uvicorn", "embedding_service:app", "--host", "0.0.0.0", "--port", "8000"]
```

### Kotlin 클라이언트

```kotlin
@Component
class EmbeddingClient(
    private val webClient: WebClient
) {
    suspend fun embed(texts: List<String>, prefix: String = "query: "): List<FloatArray> {
        return webClient.post()
            .uri("/embed")
            .bodyValue(mapOf("texts" to texts, "prefix" to prefix))
            .retrieve()
            .awaitBody<EmbedResponse>()
            .embeddings
    }
}

data class EmbedResponse(val embeddings: List<FloatArray>)
```

### 유저 취향 벡터 계산

```kotlin
/**
 * 지수 이동 평균(EMA)을 사용한 취향 벡터 업데이트
 * 최신 행동에 더 높은 가중치 부여
 */
fun updatePreferenceVector(
    currentPreference: FloatArray?,
    newProductVector: FloatArray,
    alpha: Float = 0.3f  // 새 벡터의 가중치
): FloatArray {
    if (currentPreference == null) {
        return newProductVector
    }

    return FloatArray(currentPreference.size) { i ->
        currentPreference[i] * (1 - alpha) + newProductVector[i] * alpha
    }.normalize()
}

private fun FloatArray.normalize(): FloatArray {
    val norm = sqrt(this.map { it * it }.sum())
    return this.map { it / norm }.toFloatArray()
}
```

### 입력 텍스트 구성

상품 임베딩 시 다음 정보를 조합:

```kotlin
fun buildProductText(product: Product): String {
    return buildString {
        append("passage: ")  // e5 모델 문서용 prefix
        append(product.name)
        append(" ")
        append(product.category)
        product.brand?.let { append(" $it") }
        product.description?.take(200)?.let { append(" $it") }
    }
}

// 예시 출력:
// "passage: 삼성 갤럭시 S24 울트라 전자기기 삼성전자 최신 AI 기능이 탑재된 플래그십 스마트폰..."
```

## 성능 고려사항

### 배치 처리

```kotlin
// 개별 처리 (비효율)
products.forEach { embeddingClient.embed(listOf(it.text)) }

// 배치 처리 (권장)
products.chunked(32).forEach { batch ->
    embeddingClient.embed(batch.map { it.text })
}
```

### 캐싱 전략

- **상품 벡터:** ES에 저장, 상품 정보 변경 시에만 재계산
- **유저 취향 벡터:** Redis에 TTL 24시간으로 캐싱

### GPU 리소스 (프로덕션 권장)

| 환경 | GPU | 처리량 |
|-----|-----|-------|
| 개발/테스트 | CPU only | ~20 req/s |
| 프로덕션 | NVIDIA T4 | ~500 req/s |
| 프로덕션 | NVIDIA A10G | ~1000 req/s |

## 결과 (Consequences)

### 긍정적 효과
- API 비용 없이 무제한 임베딩 생성
- 10ms 미만의 임베딩 지연 시간 (GPU 기준)
- 데이터 외부 유출 위험 없음

### 부정적 효과 / 트레이드오프
- GPU 인프라 관리 필요 → Kubernetes + GPU node pool로 관리
- 모델 업데이트 시 재배포 필요 → Blue-Green 배포로 무중단 전환
- OpenAI 대비 품질 약간 낮을 수 있음 → 실제 A/B 테스트로 검증 필요

## 관련 문서
- [ADR-001: 동시성 처리 전략](./adr-001-concurrency-strategy.md)
- [ADR-004: 벡터 저장소 선택](./adr-004-vector-storage.md)
- [Phase 3: 실시간 추천 엔진](./phase%203.md)