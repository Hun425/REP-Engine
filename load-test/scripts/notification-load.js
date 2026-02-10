/**
 * 시나리오 3: 알림 파이프라인 스트레스 테스트
 *
 * 목적: 인벤토리 이벤트 폭증 시 알림 처리량 + rate limiter 동작 확인
 *
 * 방법:
 *   Phase A (0~30s): 시뮬레이터 트래픽 500유저 (행동 데이터 축적)
 *   Phase B (30s~2m30s): 인벤토리 시뮬레이터 동시 가동 + 추천 API 20 VU 요청
 *   Phase C (2m30s~4m): 인벤토리만 유지하고 관찰
 *   Phase D (4m~5m): 전부 정지 후 쿨다운
 *
 * 관찰 대상:
 *   - notification.triggered / notification.rate.limited
 *   - notification.send.success / notification.send.failed
 *   - Redis 메모리 사용량
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SIMULATOR_URL, RECOMMENDATION_URL } from './helpers/config.js';
import { warmUserId } from './helpers/generators.js';

// 커스텀 메트릭
const phaseTransitions = new Counter('notification_phase_transitions');
const recommendationRequests = new Counter('notification_test_rec_requests');
const recommendationLatency = new Trend('notification_test_rec_latency_ms');

const WARM_USER_MAX = Number(__ENV.WARM_USER_MAX || '100');

export const options = {
    scenarios: {
        // 오케스트레이터: 시뮬레이터 시작/정지 제어
        orchestrator: {
            executor: 'shared-iterations',
            vus: 1,
            iterations: 1,
            maxDuration: '6m',
            exec: 'orchestrator',
        },
        // 추천 API 부하: Phase B에서만 활성
        recommendation_load: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 0 },   // Phase A: 대기
                { duration: '10s', target: 20 },   // ramp up
                { duration: '2m', target: 20 },    // Phase B: 20 VU 유지
                { duration: '10s', target: 0 },    // ramp down
                { duration: '2m30s', target: 0 },  // Phase C+D: 대기
            ],
            exec: 'recommendationLoad',
        },
    },
    thresholds: {
        'notification_test_rec_latency_ms': ['p(95)<1000'],
    },
};

// === 시나리오: 오케스트레이터 ===
export function orchestrator() {
    // Phase A: 시뮬레이터 트래픽 시작 (500유저, 300ms)
    console.log('[Phase A] Starting traffic simulator: 500 users, 300ms delay');
    const startRes = http.post(
        `${SIMULATOR_URL}/api/v1/simulator/start?userCount=500&delayMillis=300`
    );
    check(startRes, { 'traffic start 200': (r) => r.status === 200 });
    phaseTransitions.add(1);

    sleep(30); // 30초 행동 데이터 축적

    // Phase B: 인벤토리 시뮬레이터 동시 가동
    console.log('[Phase B] Starting inventory simulator');
    const invStartRes = http.post(`${SIMULATOR_URL}/api/v1/simulator/inventory/start`);
    check(invStartRes, { 'inventory start 200': (r) => r.status === 200 });
    phaseTransitions.add(1);

    // 2분간 관찰
    for (let i = 0; i < 12; i++) {
        sleep(10);
        const status = http.get(`${SIMULATOR_URL}/api/v1/simulator/status`);
        const invStatus = http.get(`${SIMULATOR_URL}/api/v1/simulator/inventory/status`);
        if (status.status === 200 && invStatus.status === 200) {
            console.log(`  [Phase B ${(i + 1) * 10}s] traffic=${JSON.parse(status.body).activeSessions}, inventory=${JSON.parse(invStatus.body).running}`);
        }
    }

    // Phase C: 트래픽 정지, 인벤토리만 유지
    console.log('[Phase C] Stopping traffic, keeping inventory');
    http.post(`${SIMULATOR_URL}/api/v1/simulator/stop`);
    phaseTransitions.add(1);

    // 1분 30초간 인벤토리만 관찰
    for (let i = 0; i < 9; i++) {
        sleep(10);
        const invStatus = http.get(`${SIMULATOR_URL}/api/v1/simulator/inventory/status`);
        if (invStatus.status === 200) {
            console.log(`  [Phase C ${(i + 1) * 10}s] inventory=${JSON.parse(invStatus.body).running}`);
        }
    }

    // Phase D: 전부 정지
    console.log('[Phase D] Stopping all simulators');
    http.post(`${SIMULATOR_URL}/api/v1/simulator/inventory/stop`);
    phaseTransitions.add(1);

    sleep(60); // 1분 쿨다운

    console.log('[Done] Notification stress test completed');
}

// === 시나리오: 추천 API 부하 ===
export function recommendationLoad() {
    const userId = warmUserId(WARM_USER_MAX);
    const url = `${RECOMMENDATION_URL}/api/v1/recommendations/${userId}?limit=10`;
    const res = http.get(url, { tags: { endpoint: 'notification_rec' } });

    recommendationLatency.add(res.timings.duration);
    recommendationRequests.add(1);

    check(res, {
        'rec status 200': (r) => r.status === 200,
    });

    sleep(0.5); // 500ms 간격
}
