# REP-Engine Embedding Service
# ADR-003: multilingual-e5-base Self-hosted
FROM python:3.11-slim

# Set working directory
WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements first for better caching
COPY requirements.txt .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download the model (cached in image)
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('intfloat/multilingual-e5-base')"

# Copy application code
COPY embedding_service.py .

# Environment variables
ENV MODEL_NAME=intfloat/multilingual-e5-base
ENV PORT=8000
ENV WORKERS=1

# Expose port
EXPOSE 8000

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

# Run the application
CMD ["uvicorn", "embedding_service:app", "--host", "0.0.0.0", "--port", "8000"]
