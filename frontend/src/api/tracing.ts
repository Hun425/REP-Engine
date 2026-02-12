import { apiClient } from './client'
import type {
  TraceSummary, JaegerTrace, TraceAnomaly, AnomalyType,
  TraceBookmark, CreateBookmarkRequest, AnomalyScanResult
} from './types'

const BASE = '/api/v1/tracing'

// Trace Query
export const getServices = () =>
  apiClient.get<string[]>(`${BASE}/services`).then(r => r.data)

export const searchTraces = (params: {
  service?: string
  limit?: number
  minDuration?: string
  maxDuration?: string
  start?: number
  end?: number
}) =>
  apiClient.get<TraceSummary[]>(`${BASE}/traces`, { params }).then(r => r.data)

export const getTraceDetail = (traceId: string) =>
  apiClient.get<JaegerTrace>(`${BASE}/traces/${traceId}`).then(r => r.data)

// Anomalies
export const getAnomalies = (params: {
  type?: AnomalyType
  service?: string
  from?: number
  to?: number
  page?: number
  size?: number
}) =>
  apiClient.get<TraceAnomaly[]>(`${BASE}/anomalies`, { params }).then(r => r.data)

export const triggerAnomalyScan = () =>
  apiClient.post<AnomalyScanResult>(`${BASE}/anomalies/detect`).then(r => r.data)

// Bookmarks
export const getBookmarks = () =>
  apiClient.get<TraceBookmark[]>(`${BASE}/bookmarks`).then(r => r.data)

export const addBookmark = (data: CreateBookmarkRequest) =>
  apiClient.post<{ id: string }>(`${BASE}/bookmarks`, data).then(r => r.data)

export const deleteBookmark = (id: string) =>
  apiClient.delete(`${BASE}/bookmarks/${id}`)

export const updateBookmarkNote = (id: string, note: string) =>
  apiClient.patch(`${BASE}/bookmarks/${id}`, { note })
