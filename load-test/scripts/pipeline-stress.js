/**
 * 시나리오 1: E2E 파이프라인 스트레스 테스트
 *
 * 목적: behavior-consumer 파이프라인의 한계점 탐색
 * 방법: 시뮬레이터 REST API로 유저 수를 단계적 증가
 *       100 → 500 → 1000 → 2000 → 5000 (delayMillis=200)
 *       각 단계 2분 유지 → 30초 쿨다운
 *
 * VU: 1 (오케스트레이터 역할, 실제 부하는 시뮬레이터가 생성)
 *
 * 관찰 대상 (Grafana):
 *   - Kafka consumer lag (kafka_consumergroup_lag)
 *   - ES bulk 성공/실패율 (es_bulk_success/failed)
 *   - Preference update 처리량 (preference_update_success/skipped)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SIMULATOR_URL } from './helpers/config.js';

// 커스텀 메트릭
const stageTransitions = new Counter('pipeline_stage_transitions');
const simulatorStartLatency = new Trend('simulator_start_latency_ms');
const simulatorStopLatency = new Trend('simulator_stop_latency_ms');

// 단계별 유저 수
const STAGES = [100, 500, 1000, 2000, 5000];
const STAGE_DURATION_SEC = 120; // 2분
const COOLDOWN_SEC = 30;
const DELAY_MILLIS = 200;

export const options = {
    vus: 1,
    // 총 소요 시간: (2분 + 30초) * 5단계 = 12.5분
    duration: `${(STAGE_DURATION_SEC + COOLDOWN_SEC) * STAGES.length}s`,
    thresholds: {
        'simulator_start_latency_ms': ['p(95)<5000'],
    },
};

function startSimulator(userCount, delayMillis) {
    const url = `${SIMULATOR_URL}/api/v1/simulator/start?userCount=${userCount}&delayMillis=${delayMillis}`;
    const res = http.post(url);
    simulatorStartLatency.add(res.timings.duration);
    check(res, {
        'simulator start 200': (r) => r.status === 200,
    });
    return res;
}

function stopSimulator() {
    const res = http.post(`${SIMULATOR_URL}/api/v1/simulator/stop`);
    simulatorStopLatency.add(res.timings.duration);
    check(res, {
        'simulator stop 200': (r) => r.status === 200,
    });
    return res;
}

function getStatus() {
    return http.get(`${SIMULATOR_URL}/api/v1/simulator/status`);
}

export default function () {
    for (let i = 0; i < STAGES.length; i++) {
        const userCount = STAGES[i];
        console.log(`[Stage ${i + 1}/${STAGES.length}] Starting ${userCount} users (delay=${DELAY_MILLIS}ms)`);

        // 시뮬레이터 시작
        startSimulator(userCount, DELAY_MILLIS);
        stageTransitions.add(1);

        // 2분간 유지하면서 주기적으로 상태 확인
        const checks = Math.floor(STAGE_DURATION_SEC / 10);
        for (let j = 0; j < checks; j++) {
            sleep(10);
            const statusRes = getStatus();
            if (statusRes.status === 200) {
                const status = JSON.parse(statusRes.body);
                console.log(`  [${j * 10}s] active=${status.activeSessions}, total=${status.totalEventsProduced}`);
            }
        }

        // 시뮬레이터 정지
        console.log(`[Stage ${i + 1}] Stopping simulator...`);
        stopSimulator();

        // 쿨다운 (파이프라인이 밀린 데이터 처리하는 시간)
        if (i < STAGES.length - 1) {
            console.log(`[Cooldown] ${COOLDOWN_SEC}s...`);
            sleep(COOLDOWN_SEC);
        }
    }

    console.log('[Done] Pipeline stress test completed');
}
