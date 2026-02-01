# Build stage
FROM node:20-alpine AS builder

WORKDIR /app

# 의존성 설치 (캐시 최적화)
COPY package.json package-lock.json ./
RUN npm ci

# 소스 복사 및 빌드
COPY . .
RUN npm run build

# Production stage
FROM nginx:alpine

# nginx 설정 복사
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 빌드 결과물 복사
COPY --from=builder /app/dist /usr/share/nginx/html

# 헬스체크
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:80/ || exit 1

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
