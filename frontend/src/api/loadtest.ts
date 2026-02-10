import { apiClient, API_URLS } from './client'
import type {
  LoadTestStartRequest,
  LoadTestStatus,
  LoadTestResult,
  LoadTestResultSummary,
  NoteUpdateRequest,
} from './types'

export async function startLoadTest(request: LoadTestStartRequest): Promise<LoadTestStatus> {
  const response = await apiClient.post<LoadTestStatus>(
    `${API_URLS.loadTest}/start`,
    request
  )
  return response.data
}

export async function getLoadTestStatus(): Promise<LoadTestStatus> {
  const response = await apiClient.get<LoadTestStatus>(
    `${API_URLS.loadTest}/status`
  )
  return response.data
}

export async function stopLoadTest(): Promise<LoadTestStatus> {
  const response = await apiClient.post<LoadTestStatus>(
    `${API_URLS.loadTest}/stop`
  )
  return response.data
}

export async function getLoadTestResults(): Promise<LoadTestResultSummary[]> {
  const response = await apiClient.get<LoadTestResultSummary[]>(
    `${API_URLS.loadTest}/results`
  )
  return response.data
}

export async function getLoadTestResult(id: string): Promise<LoadTestResult> {
  const response = await apiClient.get<LoadTestResult>(
    `${API_URLS.loadTest}/results/${id}`
  )
  return response.data
}

export async function deleteLoadTestResult(id: string): Promise<void> {
  await apiClient.delete(`${API_URLS.loadTest}/results/${id}`)
}

export async function updateLoadTestNote(id: string, note: string): Promise<void> {
  const request: NoteUpdateRequest = { note }
  await apiClient.patch(`${API_URLS.loadTest}/results/${id}/note`, request)
}
