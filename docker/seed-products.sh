#!/bin/bash
#
# 상품 데이터 시딩 스크립트
#
# ES product_index에 테스트용 상품 데이터를 생성합니다.
# Embedding Service를 호출하여 상품 벡터를 생성합니다.
#
# 사용법:
#   ./seed-products.sh [상품 수]
#   ./seed-products.sh 100   # 100개 상품 생성
#
# 의존성:
#   - Elasticsearch가 실행 중이어야 함
#   - Embedding Service가 실행 중이어야 함
#   - curl, jq 설치 필요
#

set -e

ES_HOST="${ES_HOST:-http://localhost:9200}"
EMBEDDING_HOST="${EMBEDDING_HOST:-http://localhost:8000}"
PRODUCT_COUNT="${1:-100}"
BATCH_SIZE=20

echo "========================================"
echo "REP-Engine Product Data Seeding"
echo "========================================"
echo "Elasticsearch: $ES_HOST"
echo "Embedding Service: $EMBEDDING_HOST"
echo "Product Count: $PRODUCT_COUNT"
echo "========================================"

# 헬스체크
echo ""
echo "[1/4] Checking service health..."

if ! curl -s "$ES_HOST/_cluster/health" | grep -q '"status"'; then
    echo "ERROR: Elasticsearch is not available at $ES_HOST"
    exit 1
fi
echo "  - Elasticsearch: OK"

if ! curl -s "$EMBEDDING_HOST/health" | grep -q '"status":"ok"'; then
    echo "ERROR: Embedding Service is not available at $EMBEDDING_HOST"
    exit 1
fi
echo "  - Embedding Service: OK"

# 카테고리 및 상품명 정의
CATEGORIES=("ELECTRONICS" "FASHION" "FOOD" "BEAUTY" "SPORTS" "HOME" "BOOKS")

declare -A PRODUCTS_BY_CATEGORY
PRODUCTS_BY_CATEGORY["ELECTRONICS"]="스마트폰 노트북 태블릿 이어폰 충전기 스마트워치 모니터 키보드 마우스 스피커"
PRODUCTS_BY_CATEGORY["FASHION"]="운동화 청바지 티셔츠 원피스 자켓 코트 스니커즈 백팩 모자 선글라스"
PRODUCTS_BY_CATEGORY["FOOD"]="과자 라면 커피 음료 과일 고기 샐러드 빵 초콜릿 견과류"
PRODUCTS_BY_CATEGORY["BEAUTY"]="로션 선크림 립스틱 파운데이션 마스크팩 샴푸 향수 아이크림 토너 세럼"
PRODUCTS_BY_CATEGORY["SPORTS"]="요가매트 덤벨 러닝화 스포츠웨어 자전거 테니스라켓 축구공 수영복 골프채 배드민턴"
PRODUCTS_BY_CATEGORY["HOME"]="쿠션 이불 조명 수납함 커튼 러그 식기세트 냄비 후라이팬 청소기"
PRODUCTS_BY_CATEGORY["BOOKS"]="소설 자기계발 경제 역사 과학 에세이 만화 요리책 여행 외국어"

declare -A BRANDS_BY_CATEGORY
BRANDS_BY_CATEGORY["ELECTRONICS"]="삼성 애플 LG 소니 로지텍"
BRANDS_BY_CATEGORY["FASHION"]="나이키 아디다스 유니클로 자라 H&M"
BRANDS_BY_CATEGORY["FOOD"]="농심 오뚜기 CJ 롯데 동원"
BRANDS_BY_CATEGORY["BEAUTY"]="아모레퍼시픽 LG생활건강 로레알 에스티로더 이니스프리"
BRANDS_BY_CATEGORY["SPORTS"]="나이키 아디다스 언더아머 뉴발란스 푸마"
BRANDS_BY_CATEGORY["HOME"]="이케아 무인양품 한샘 까사미아 리바트"
BRANDS_BY_CATEGORY["BOOKS"]="민음사 창비 문학동네 위즈덤하우스 알에이치코리아"

# 임시 파일 생성
PRODUCTS_FILE=$(mktemp)
BULK_FILE=$(mktemp)
trap "rm -f $PRODUCTS_FILE $BULK_FILE" EXIT

echo ""
echo "[2/4] Generating $PRODUCT_COUNT products..."

# 상품 생성
for i in $(seq 1 $PRODUCT_COUNT); do
    # 랜덤 카테고리 선택
    CATEGORY=${CATEGORIES[$((RANDOM % ${#CATEGORIES[@]}))]}

    # 해당 카테고리 상품명 배열
    IFS=' ' read -ra PRODS <<< "${PRODUCTS_BY_CATEGORY[$CATEGORY]}"
    PRODUCT_NAME=${PRODS[$((RANDOM % ${#PRODS[@]}))]}

    # 해당 카테고리 브랜드 배열
    IFS=' ' read -ra BRNDS <<< "${BRANDS_BY_CATEGORY[$CATEGORY]}"
    BRAND=${BRNDS[$((RANDOM % ${#BRNDS[@]}))]}

    # 랜덤 가격 (10000 ~ 500000)
    PRICE=$((10000 + RANDOM % 490000))

    # 랜덤 재고 (0 ~ 1000)
    STOCK=$((RANDOM % 1001))

    # 상품 ID
    PRODUCT_ID="PROD-${CATEGORY:0:3}-$(printf '%05d' $i)"

    # 상품 전체 이름
    FULL_NAME="${BRAND} ${PRODUCT_NAME} ${CATEGORY}"

    # 설명 생성
    DESCRIPTION="${BRAND}에서 만든 고품질 ${PRODUCT_NAME}입니다. ${CATEGORY} 카테고리의 인기 상품."

    # JSON으로 저장
    echo "{\"id\":\"$PRODUCT_ID\",\"name\":\"$FULL_NAME\",\"category\":\"$CATEGORY\",\"brand\":\"$BRAND\",\"price\":$PRICE,\"stock\":$STOCK,\"description\":\"$DESCRIPTION\"}" >> $PRODUCTS_FILE

    if [ $((i % 50)) -eq 0 ]; then
        echo "  - Generated $i / $PRODUCT_COUNT products"
    fi
done

echo "  - Generated $PRODUCT_COUNT products total"

echo ""
echo "[3/4] Generating embeddings and preparing bulk request..."

# 배치 단위로 임베딩 생성 및 ES bulk 요청 준비
total_lines=$(wc -l < $PRODUCTS_FILE)
batch_num=0

while IFS= read -r line || [ -n "$line" ]; do
    batch_products+=("$line")

    if [ ${#batch_products[@]} -ge $BATCH_SIZE ]; then
        batch_num=$((batch_num + 1))

        # 텍스트 배열 생성 (passage: prefix 사용)
        texts_json="["
        for prod in "${batch_products[@]}"; do
            name=$(echo "$prod" | jq -r '.name')
            category=$(echo "$prod" | jq -r '.category')
            description=$(echo "$prod" | jq -r '.description')
            text="${name} ${category} ${description}"
            texts_json="${texts_json}\"${text}\","
        done
        texts_json="${texts_json%,}]"

        # Embedding Service 호출
        embed_response=$(curl -s -X POST "$EMBEDDING_HOST/embed" \
            -H "Content-Type: application/json" \
            -d "{\"texts\": $texts_json, \"prefix\": \"passage: \"}")

        # 임베딩 배열 추출
        embeddings=$(echo "$embed_response" | jq -c '.embeddings')

        if [ "$embeddings" == "null" ] || [ -z "$embeddings" ]; then
            echo "ERROR: Failed to get embeddings for batch $batch_num"
            echo "Response: $embed_response"
            exit 1
        fi

        # Bulk 요청 생성
        for j in "${!batch_products[@]}"; do
            prod="${batch_products[$j]}"
            id=$(echo "$prod" | jq -r '.id')
            name=$(echo "$prod" | jq -r '.name')
            category=$(echo "$prod" | jq -r '.category')
            brand=$(echo "$prod" | jq -r '.brand')
            price=$(echo "$prod" | jq -r '.price')
            stock=$(echo "$prod" | jq -r '.stock')
            description=$(echo "$prod" | jq -r '.description')
            vector=$(echo "$embeddings" | jq -c ".[$j]")

            # Bulk index 액션
            echo "{\"index\":{\"_index\":\"product_index\",\"_id\":\"$id\"}}" >> $BULK_FILE
            echo "{\"productId\":\"$id\",\"productName\":\"$name\",\"category\":\"$category\",\"brand\":\"$brand\",\"price\":$price,\"stock\":$stock,\"description\":\"$description\",\"productVector\":$vector,\"createdAt\":$(date +%s000),\"updatedAt\":$(date +%s000)}" >> $BULK_FILE
        done

        echo "  - Processed batch $batch_num ($(($batch_num * $BATCH_SIZE)) products)"
        batch_products=()
    fi
done < "$PRODUCTS_FILE"

# 남은 배치 처리
if [ ${#batch_products[@]} -gt 0 ]; then
    batch_num=$((batch_num + 1))

    texts_json="["
    for prod in "${batch_products[@]}"; do
        name=$(echo "$prod" | jq -r '.name')
        category=$(echo "$prod" | jq -r '.category')
        description=$(echo "$prod" | jq -r '.description')
        text="${name} ${category} ${description}"
        texts_json="${texts_json}\"${text}\","
    done
    texts_json="${texts_json%,}]"

    embed_response=$(curl -s -X POST "$EMBEDDING_HOST/embed" \
        -H "Content-Type: application/json" \
        -d "{\"texts\": $texts_json, \"prefix\": \"passage: \"}")

    embeddings=$(echo "$embed_response" | jq -c '.embeddings')

    for j in "${!batch_products[@]}"; do
        prod="${batch_products[$j]}"
        id=$(echo "$prod" | jq -r '.id')
        name=$(echo "$prod" | jq -r '.name')
        category=$(echo "$prod" | jq -r '.category')
        brand=$(echo "$prod" | jq -r '.brand')
        price=$(echo "$prod" | jq -r '.price')
        stock=$(echo "$prod" | jq -r '.stock')
        description=$(echo "$prod" | jq -r '.description')
        vector=$(echo "$embeddings" | jq -c ".[$j]")

        echo "{\"index\":{\"_index\":\"product_index\",\"_id\":\"$id\"}}" >> $BULK_FILE
        echo "{\"productId\":\"$id\",\"productName\":\"$name\",\"category\":\"$category\",\"brand\":\"$brand\",\"price\":$price,\"stock\":$stock,\"description\":\"$description\",\"productVector\":$vector,\"createdAt\":$(date +%s000),\"updatedAt\":$(date +%s000)}" >> $BULK_FILE
    done

    echo "  - Processed final batch (remaining products)"
fi

echo ""
echo "[4/4] Sending bulk request to Elasticsearch..."

# ES Bulk API 호출
response=$(curl -s -X POST "$ES_HOST/_bulk" \
    -H "Content-Type: application/x-ndjson" \
    --data-binary @$BULK_FILE)

# 결과 확인
errors=$(echo "$response" | jq '.errors')
took=$(echo "$response" | jq '.took')

if [ "$errors" == "true" ]; then
    echo "WARNING: Some documents failed to index"
    echo "$response" | jq '.items[] | select(.index.error != null) | .index.error' | head -5
else
    echo "SUCCESS: All documents indexed successfully"
fi

echo ""
echo "========================================"
echo "Seeding completed!"
echo "  - Time taken: ${took}ms"
echo "  - Documents: $PRODUCT_COUNT"
echo "========================================"

# 확인
echo ""
echo "Verifying indexed documents..."
count=$(curl -s "$ES_HOST/product_index/_count" | jq '.count')
echo "  - product_index document count: $count"
