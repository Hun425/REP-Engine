import { apiClient, API_URLS } from './client'
import type { SimulatorStatus, SimulatorStartRequest } from './types'

/**
 * 시뮬레이터 상태 조회
 */
export async function getSimulatorStatus(): Promise<SimulatorStatus> {
  const response = await apiClient.get<SimulatorStatus>(
    `${API_URLS.simulator}/status`
  )
  return response.data
}

/**
 * 시뮬레이터 시작
 */
export async function startSimulator(
  config?: SimulatorStartRequest
): Promise<SimulatorStatus> {
  const response = await apiClient.post<SimulatorStatus>(
    `${API_URLS.simulator}/start`,
    null,
    {
      params: {
        userCount: config?.userCount ?? 100,
        delayMillis: config?.delayMillis ?? 1000,
      },
    }
  )
  return response.data
}

/**
 * 시뮬레이터 정지
 */
export async function stopSimulator(): Promise<SimulatorStatus> {
  const response = await apiClient.post<SimulatorStatus>(
    `${API_URLS.simulator}/stop`
  )
  return response.data
}
