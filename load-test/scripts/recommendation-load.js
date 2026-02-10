/**
 * 시나리오 2: 추천 API 부하 테스트
 *
 * 목적: KNN 검색 P99 레이턴시 한계점 + Redis 캐시 효과 측정
 *
 * 타겟 엔드포인트:
 *   - GET /api/v1/recommendations/{userId}?limit=10&excludeViewed=true  (KNN 경로)
 *   - GET /api/v1/recommendations/popular?limit=10                     (인기 상품 경로)
 *   - Cold start 유저 (높은 ID) → popularity fallback 경로
 *
 * 부하 패턴: ramping-vus
 *   0→10 (30s) → 50 (1m) → 100 (2m) → 200 (2m) → 300 (1m) → 0 (30s)
 *
 * 임계값: P95 < 500ms, P99 < 1000ms, 에러율 < 5%
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import { RECOMMENDATION_URL } from './helpers/config.js';
import { randomUserId, warmUserId, coldUserId } from './helpers/generators.js';

// 커스텀 메트릭
const recommendationLatency = new Trend('recommendation_latency_ms');
const knnRequests = new Counter('knn_requests');
const popularRequests = new Counter('popular_requests');
const coldStartRequests = new Counter('cold_start_requests');
const recommendationErrors = new Counter('recommendation_errors');
const recommendationErrorRate = new Rate('recommendation_error_rate');

// 시뮬레이터가 미리 생성한 유저 수 (사전 시딩 필요)
const WARM_USER_MAX = Number(__ENV.WARM_USER_MAX || '100');

export const options = {
    stages: [
        { duration: '30s', target: 10 },
        { duration: '1m', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '2m', target: 200 },
        { duration: '1m', target: 300 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        'recommendation_latency_ms': ['p(95)<500', 'p(99)<1000'],
        'recommendation_error_rate': ['rate<0.05'],
        'http_req_duration': ['p(95)<600'],
    },
};

export default function () {
    const roll = Math.random();

    if (roll < 0.5) {
        // 50%: warm 유저 → KNN 경로 (선호 벡터 존재)
        const userId = warmUserId(WARM_USER_MAX);
        const url = `${RECOMMENDATION_URL}/api/v1/recommendations/${userId}?limit=10&excludeViewed=true`;
        const res = http.get(url, { tags: { endpoint: 'knn' } });

        recommendationLatency.add(res.timings.duration);
        knnRequests.add(1);

        const success = check(res, {
            'knn status 200': (r) => r.status === 200,
            'knn has items': (r) => {
                if (r.status !== 200) return false;
                const body = JSON.parse(r.body);
                return body.items && body.items.length > 0;
            },
        });

        if (!success) {
            recommendationErrors.add(1);
            recommendationErrorRate.add(1);
        } else {
            recommendationErrorRate.add(0);
        }

    } else if (roll < 0.8) {
        // 30%: popular 엔드포인트
        const url = `${RECOMMENDATION_URL}/api/v1/recommendations/popular?limit=10`;
        const res = http.get(url, { tags: { endpoint: 'popular' } });

        recommendationLatency.add(res.timings.duration);
        popularRequests.add(1);

        const success = check(res, {
            'popular status 200': (r) => r.status === 200,
        });

        if (!success) {
            recommendationErrors.add(1);
            recommendationErrorRate.add(1);
        } else {
            recommendationErrorRate.add(0);
        }

    } else {
        // 20%: cold start 유저 → popularity fallback
        const userId = coldUserId();
        const url = `${RECOMMENDATION_URL}/api/v1/recommendations/${userId}?limit=10`;
        const res = http.get(url, { tags: { endpoint: 'cold_start' } });

        recommendationLatency.add(res.timings.duration);
        coldStartRequests.add(1);

        const success = check(res, {
            'cold start status 200': (r) => r.status === 200,
        });

        if (!success) {
            recommendationErrors.add(1);
            recommendationErrorRate.add(1);
        } else {
            recommendationErrorRate.add(0);
        }
    }

    sleep(0.1); // 100ms 간격
}
