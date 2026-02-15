import { apiClient, API_URLS } from './client'
import type {
  TraceSummary, JaegerTrace, TraceAnomaly, AnomalyType,
  TraceBookmark, CreateBookmarkRequest, AnomalyScanResult
} from './types'

// Trace Query
export const getServices = () =>
  apiClient.get<string[]>(`${API_URLS.tracing}/services`).then(r => r.data)

export const searchTraces = (params: {
  service?: string
  limit?: number
  minDuration?: string
  maxDuration?: string
  start?: number
  end?: number
}) =>
  apiClient.get<TraceSummary[]>(`${API_URLS.tracing}/traces`, { params }).then(r => r.data)

export const getTraceDetail = (traceId: string) =>
  apiClient.get<JaegerTrace>(`${API_URLS.tracing}/traces/${traceId}`).then(r => r.data)

// Anomalies
export const getAnomalies = (params: {
  type?: AnomalyType
  service?: string
  from?: number
  to?: number
  page?: number
  size?: number
}) =>
  apiClient.get<TraceAnomaly[]>(`${API_URLS.tracing}/anomalies`, { params }).then(r => r.data)

export const triggerAnomalyScan = () =>
  apiClient.post<AnomalyScanResult>(`${API_URLS.tracing}/anomalies/detect`).then(r => r.data)

// Bookmarks
export const getBookmarks = () =>
  apiClient.get<TraceBookmark[]>(`${API_URLS.tracing}/bookmarks`).then(r => r.data)

export const addBookmark = (data: CreateBookmarkRequest) =>
  apiClient.post<{ id: string }>(`${API_URLS.tracing}/bookmarks`, data).then(r => r.data)

export const deleteBookmark = (id: string) =>
  apiClient.delete(`${API_URLS.tracing}/bookmarks/${id}`)

export const updateBookmarkNote = (id: string, note: string) =>
  apiClient.patch(`${API_URLS.tracing}/bookmarks/${id}`, { note })
