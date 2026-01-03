"""
Embedding Service - multilingual-e5-base

상품 텍스트를 384차원 벡터로 변환하는 임베딩 서비스입니다.
ADR-003에 따라 Self-hosted Sentence-Transformers를 사용합니다.

@see docs/adr-003-embedding-model.md
"""
import os
import logging
from typing import List, Optional
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
import numpy as np

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Global model instance
model = None


class EmbedRequest(BaseModel):
    """임베딩 요청"""
    texts: List[str] = Field(..., min_length=1, max_length=100)
    prefix: str = Field(default="query: ", description="e5 모델용 prefix (query: 또는 passage:)")


class EmbedResponse(BaseModel):
    """임베딩 응답"""
    embeddings: List[List[float]]
    dims: int = 384


class HealthResponse(BaseModel):
    """헬스체크 응답"""
    status: str
    model: str
    dims: int


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application startup/shutdown lifecycle"""
    global model

    # Startup: Load model
    model_name = os.getenv("MODEL_NAME", "intfloat/multilingual-e5-base")
    logger.info(f"Loading embedding model: {model_name}")

    try:
        from sentence_transformers import SentenceTransformer
        model = SentenceTransformer(model_name)
        logger.info(f"Model loaded successfully. Dims: {model.get_sentence_embedding_dimension()}")
    except Exception as e:
        logger.error(f"Failed to load model: {e}")
        raise

    yield

    # Shutdown: Cleanup
    logger.info("Shutting down embedding service...")
    model = None


app = FastAPI(
    title="REP-Engine Embedding Service",
    description="상품 및 유저 취향을 위한 텍스트 임베딩 API",
    version="1.0.0",
    lifespan=lifespan
)


@app.post("/embed", response_model=EmbedResponse)
async def embed(request: EmbedRequest):
    """
    텍스트를 벡터로 변환합니다.

    - prefix="query: " → 검색 쿼리용 (유저 취향)
    - prefix="passage: " → 문서용 (상품 정보)
    """
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    try:
        # e5 모델은 prefix 추가 권장
        prefixed_texts = [request.prefix + text for text in request.texts]

        # 임베딩 생성 (정규화 적용)
        embeddings = model.encode(
            prefixed_texts,
            normalize_embeddings=True,  # Cosine similarity를 위해 정규화
            show_progress_bar=False
        )

        # numpy array → list 변환
        embeddings_list = embeddings.tolist()

        logger.debug(f"Generated {len(embeddings_list)} embeddings")

        return EmbedResponse(
            embeddings=embeddings_list,
            dims=len(embeddings_list[0]) if embeddings_list else 384
        )

    except Exception as e:
        logger.error(f"Embedding failed: {e}")
        raise HTTPException(status_code=500, detail=f"Embedding failed: {str(e)}")


@app.get("/embed/single")
async def embed_single(text: str, prefix: str = "query: "):
    """단일 텍스트 임베딩 (쿼리 파라미터 방식)"""
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    try:
        prefixed_text = prefix + text
        embedding = model.encode(
            prefixed_text,
            normalize_embeddings=True,
            show_progress_bar=False
        )

        return {
            "embedding": embedding.tolist(),
            "dims": len(embedding)
        }

    except Exception as e:
        logger.error(f"Embedding failed: {e}")
        raise HTTPException(status_code=500, detail=f"Embedding failed: {str(e)}")


@app.get("/health", response_model=HealthResponse)
async def health():
    """헬스체크 엔드포인트"""
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    return HealthResponse(
        status="ok",
        model="multilingual-e5-base",
        dims=model.get_sentence_embedding_dimension()
    )


@app.get("/")
async def root():
    """루트 엔드포인트"""
    return {
        "service": "REP-Engine Embedding Service",
        "version": "1.0.0",
        "endpoints": ["/embed", "/embed/single", "/health"]
    }


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 8000))
    workers = int(os.getenv("WORKERS", 1))
    uvicorn.run(
        "embedding_service:app",
        host="0.0.0.0",
        port=port,
        workers=workers,
        log_level="info"
    )
