#!/usr/bin/env python3
"""
상품 데이터 시딩 스크립트

ES product_index에 테스트용 상품 데이터를 생성합니다.
Embedding Service를 호출하여 상품 벡터를 생성합니다.

사용법:
    python seed_products.py [상품 수]
    python seed_products.py 100   # 100개 상품 생성

환경 변수:
    ES_HOST: Elasticsearch 호스트 (기본값: http://localhost:9200)
    EMBEDDING_HOST: Embedding Service 호스트 (기본값: http://localhost:8000)

의존성:
    pip install requests
"""

import os
import sys
import json
import random
import time
from typing import List, Dict, Any

try:
    import requests
except ImportError:
    print("ERROR: requests 패키지가 필요합니다. 'pip install requests' 실행 후 다시 시도하세요.")
    sys.exit(1)

# 설정
ES_HOST = os.getenv("ES_HOST", "http://localhost:9200")
EMBEDDING_HOST = os.getenv("EMBEDDING_HOST", "http://localhost:8000")
BATCH_SIZE = 20

# 카테고리별 상품 데이터
CATEGORIES = ["ELECTRONICS", "FASHION", "FOOD", "BEAUTY", "SPORTS", "HOME", "BOOKS"]

PRODUCTS_BY_CATEGORY = {
    "ELECTRONICS": ["스마트폰", "노트북", "태블릿", "이어폰", "충전기", "스마트워치", "모니터", "키보드", "마우스", "스피커"],
    "FASHION": ["운동화", "청바지", "티셔츠", "원피스", "자켓", "코트", "스니커즈", "백팩", "모자", "선글라스"],
    "FOOD": ["과자", "라면", "커피", "음료", "과일", "고기", "샐러드", "빵", "초콜릿", "견과류"],
    "BEAUTY": ["로션", "선크림", "립스틱", "파운데이션", "마스크팩", "샴푸", "향수", "아이크림", "토너", "세럼"],
    "SPORTS": ["요가매트", "덤벨", "러닝화", "스포츠웨어", "자전거", "테니스라켓", "축구공", "수영복", "골프채", "배드민턴"],
    "HOME": ["쿠션", "이불", "조명", "수납함", "커튼", "러그", "식기세트", "냄비", "후라이팬", "청소기"],
    "BOOKS": ["소설", "자기계발", "경제", "역사", "과학", "에세이", "만화", "요리책", "여행", "외국어"]
}

BRANDS_BY_CATEGORY = {
    "ELECTRONICS": ["삼성", "애플", "LG", "소니", "로지텍"],
    "FASHION": ["나이키", "아디다스", "유니클로", "자라", "H&M"],
    "FOOD": ["농심", "오뚜기", "CJ", "롯데", "동원"],
    "BEAUTY": ["아모레퍼시픽", "LG생활건강", "로레알", "에스티로더", "이니스프리"],
    "SPORTS": ["나이키", "아디다스", "언더아머", "뉴발란스", "푸마"],
    "HOME": ["이케아", "무인양품", "한샘", "까사미아", "리바트"],
    "BOOKS": ["민음사", "창비", "문학동네", "위즈덤하우스", "알에이치코리아"]
}


def check_health() -> bool:
    """서비스 헬스체크"""
    print("\n[1/4] Checking service health...")

    # Elasticsearch 체크
    try:
        resp = requests.get(f"{ES_HOST}/_cluster/health", timeout=5)
        if resp.status_code == 200:
            print("  - Elasticsearch: OK")
        else:
            print(f"  - Elasticsearch: FAILED (status={resp.status_code})")
            return False
    except Exception as e:
        print(f"  - Elasticsearch: FAILED ({e})")
        return False

    # Embedding Service 체크
    try:
        resp = requests.get(f"{EMBEDDING_HOST}/health", timeout=5)
        if resp.status_code == 200 and resp.json().get("status") == "ok":
            print("  - Embedding Service: OK")
        else:
            print(f"  - Embedding Service: FAILED (status={resp.status_code})")
            return False
    except Exception as e:
        print(f"  - Embedding Service: FAILED ({e})")
        return False

    return True


def generate_products(count: int) -> List[Dict[str, Any]]:
    """상품 데이터 생성"""
    print(f"\n[2/4] Generating {count} products...")
    products = []

    for i in range(1, count + 1):
        category = random.choice(CATEGORIES)
        product_name = random.choice(PRODUCTS_BY_CATEGORY[category])
        brand = random.choice(BRANDS_BY_CATEGORY[category])
        price = random.randint(10000, 500000)
        stock = random.randint(0, 1000)

        product_id = f"PROD-{category[:3]}-{i:05d}"
        full_name = f"{brand} {product_name}"
        description = f"{brand}에서 만든 고품질 {product_name}입니다. {category} 카테고리의 인기 상품."

        products.append({
            "id": product_id,
            "name": full_name,
            "category": category,
            "brand": brand,
            "price": price,
            "stock": stock,
            "description": description
        })

        if i % 50 == 0:
            print(f"  - Generated {i} / {count} products")

    print(f"  - Generated {count} products total")
    return products


def get_embeddings(texts: List[str]) -> List[List[float]]:
    """Embedding Service 호출"""
    resp = requests.post(
        f"{EMBEDDING_HOST}/embed",
        json={"texts": texts, "prefix": "passage: "},
        timeout=30
    )
    resp.raise_for_status()
    return resp.json()["embeddings"]


def create_bulk_request(products: List[Dict[str, Any]]) -> str:
    """배치 단위로 임베딩 생성 및 Bulk 요청 준비"""
    print(f"\n[3/4] Generating embeddings and preparing bulk request...")

    bulk_lines = []
    current_time = int(time.time() * 1000)

    for batch_start in range(0, len(products), BATCH_SIZE):
        batch = products[batch_start:batch_start + BATCH_SIZE]

        # 텍스트 생성
        texts = [
            f"{p['name']} {p['category']} {p['description']}"
            for p in batch
        ]

        # 임베딩 생성
        embeddings = get_embeddings(texts)

        # Bulk 요청 생성
        for i, product in enumerate(batch):
            # Index action
            action = {"index": {"_index": "product_index", "_id": product["id"]}}
            bulk_lines.append(json.dumps(action))

            # Document
            doc = {
                "productId": product["id"],
                "productName": product["name"],
                "category": product["category"],
                "brand": product["brand"],
                "price": product["price"],
                "stock": product["stock"],
                "description": product["description"],
                "productVector": embeddings[i],
                "createdAt": current_time,
                "updatedAt": current_time
            }
            bulk_lines.append(json.dumps(doc))

        batch_num = (batch_start // BATCH_SIZE) + 1
        print(f"  - Processed batch {batch_num} ({min(batch_start + BATCH_SIZE, len(products))} products)")

    return "\n".join(bulk_lines) + "\n"


def send_bulk_request(bulk_data: str) -> Dict[str, Any]:
    """Elasticsearch Bulk API 호출"""
    print("\n[4/4] Sending bulk request to Elasticsearch...")

    resp = requests.post(
        f"{ES_HOST}/_bulk",
        data=bulk_data.encode('utf-8'),
        headers={"Content-Type": "application/x-ndjson"},
        timeout=60
    )
    resp.raise_for_status()
    return resp.json()


def main():
    # 상품 수 설정
    product_count = int(sys.argv[1]) if len(sys.argv) > 1 else 100

    print("=" * 40)
    print("REP-Engine Product Data Seeding")
    print("=" * 40)
    print(f"Elasticsearch: {ES_HOST}")
    print(f"Embedding Service: {EMBEDDING_HOST}")
    print(f"Product Count: {product_count}")
    print("=" * 40)

    # 헬스체크
    if not check_health():
        print("\nERROR: Service health check failed. Please start all services first.")
        sys.exit(1)

    # 상품 생성
    products = generate_products(product_count)

    # Bulk 요청 생성
    bulk_data = create_bulk_request(products)

    # ES로 전송
    result = send_bulk_request(bulk_data)

    # 결과 확인
    took = result.get("took", 0)
    errors = result.get("errors", False)

    if errors:
        print("WARNING: Some documents failed to index")
        for item in result.get("items", [])[:5]:
            if "error" in item.get("index", {}):
                print(f"  - {item['index']['error']}")
    else:
        print("SUCCESS: All documents indexed successfully")

    print("\n" + "=" * 40)
    print("Seeding completed!")
    print(f"  - Time taken: {took}ms")
    print(f"  - Documents: {product_count}")
    print("=" * 40)

    # 확인
    print("\nVerifying indexed documents...")
    count_resp = requests.get(f"{ES_HOST}/product_index/_count")
    count = count_resp.json().get("count", 0)
    print(f"  - product_index document count: {count}")


if __name__ == "__main__":
    main()
