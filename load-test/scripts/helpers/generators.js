// k6 ID 생성 유틸리티 - 시뮬레이터 UserSession.kt 포맷 일치

import { CATEGORIES, CATEGORY_PREFIXES, PRODUCT_COUNT_PER_CATEGORY } from './config.js';

/**
 * 랜덤 유저 ID 생성
 * 포맷: USER-XXXXXX (UserSession.kt: "USER-${i.toString().padStart(6, '0')}")
 * @param {number} max - 최대 유저 번호
 * @returns {string} e.g. "USER-000042"
 */
export function randomUserId(max) {
    const num = Math.floor(Math.random() * max) + 1;
    return `USER-${String(num).padStart(6, '0')}`;
}

/**
 * 시뮬레이터가 생성한 유저 중 선택 (선호 벡터 존재 가능성 높은 low-ID 유저)
 * @param {number} max - 시뮬레이터에서 생성한 최대 유저 수
 * @returns {string} e.g. "USER-000005"
 */
export function warmUserId(max) {
    // 앞쪽 유저일수록 행동 데이터가 많을 가능성이 높음
    const bound = Math.min(max, 50);
    const num = Math.floor(Math.random() * bound) + 1;
    return `USER-${String(num).padStart(6, '0')}`;
}

/**
 * 랜덤 상품 ID 생성
 * 포맷: PROD-{category[:3]}-{00001~productCount} (ProductCatalog.kt 참고)
 * @returns {string} e.g. "PROD-ELE-00042"
 */
export function randomProductId() {
    const category = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
    const prefix = CATEGORY_PREFIXES[category];
    const num = Math.floor(Math.random() * PRODUCT_COUNT_PER_CATEGORY) + 1;
    return `PROD-${prefix}-${String(num).padStart(5, '0')}`;
}

/**
 * Cold start 유저 ID 생성 (높은 번호 → 선호 벡터 미존재)
 * @returns {string} e.g. "USER-099999"
 */
export function coldUserId() {
    const num = 90000 + Math.floor(Math.random() * 10000);
    return `USER-${String(num).padStart(6, '0')}`;
}
