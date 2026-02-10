// k6 공통 상수 - 서비스 URL 및 카테고리 설정

export const SIMULATOR_URL = __ENV.SIMULATOR_URL || 'http://host.docker.internal:8084';
export const RECOMMENDATION_URL = __ENV.RECOMMENDATION_URL || 'http://host.docker.internal:8080';

export const CATEGORIES = [
    'ELECTRONICS',
    'FASHION',
    'FOOD',
    'BEAUTY',
    'SPORTS',
    'HOME',
    'BOOKS',
];

// 카테고리 접두사 (PROD-{prefix}-XXXXX)
export const CATEGORY_PREFIXES = {
    ELECTRONICS: 'ELE',
    FASHION: 'FAS',
    FOOD: 'FOO',
    BEAUTY: 'BEA',
    SPORTS: 'SPO',
    HOME: 'HOM',
    BOOKS: 'BOO',
};

export const PRODUCT_COUNT_PER_CATEGORY = 100;
