// ============================================
// Recommendation API Types
// ============================================

export interface ProductRecommendation {
  productId: string
  productName: string
  category: string
  price: number
  score: number
}

export interface RecommendationResponse {
  userId: string
  recommendations: ProductRecommendation[]
  strategy: 'knn' | 'popularity' | 'category_best' | 'fallback'
  latencyMs: number
}

export interface RecommendationParams {
  userId: string
  limit?: number
  category?: string
  excludeViewed?: boolean
}

// ============================================
// Simulator API Types
// ============================================

export interface SimulatorStatus {
  running: boolean
  totalEventsSent: number
  userCount: number
  delayMillis: number
}

export interface SimulatorStartRequest {
  userCount?: number
  delayMillis?: number
}

export interface InventorySimulatorStatus {
  running: boolean
  totalEventsSent: number
  intervalMs: number
  catalogSize: number
}

// ============================================
// Actuator Types
// ============================================

export interface ActuatorHealth {
  status: 'UP' | 'DOWN' | 'UNKNOWN'
  components?: Record<string, { status: string; details?: Record<string, unknown> }>
}

export interface ActuatorMetricMeasurement {
  statistic: string
  value: number
}

export interface ActuatorMetricResponse {
  name: string
  measurements: ActuatorMetricMeasurement[]
  availableTags: { tag: string; values: string[] }[]
}

export interface BehaviorConsumerMetrics {
  eventsProcessed: number
  bulkSuccess: number
  bulkFailed: number
  preferenceUpdateSuccess: number
  preferenceUpdateSkipped: number
  dlqSent: number
}

export interface NotificationMetrics {
  eventDetected: number
  triggered: number
  rateLimited: number
  sendSuccess: number
  sendFailed: number
  historySaveSuccess: number
}

export interface ServiceHealth {
  name: string
  status: 'UP' | 'DOWN' | 'UNKNOWN' | 'OFFLINE'
}

// ============================================
// Load Test Types
// ============================================

export type LoadTestScenario = 'PIPELINE_STRESS' | 'RECOMMENDATION_LOAD' | 'NOTIFICATION_LOAD'
export type LoadTestPhase = 'NOT_STARTED' | 'RUNNING' | 'STOPPING' | 'COMPLETED' | 'FAILED'

export interface LoadTestStartRequest {
  scenario: LoadTestScenario
  config: LoadTestConfig
}

export interface LoadTestConfig {
  stages?: StageConfig[]
  delayMillis?: number
  concurrentUsers?: number
  durationSec?: number
  requestIntervalMs?: number
  inventoryEnabled?: boolean
}

export interface StageConfig {
  userCount: number
  durationSec: number
  cooldownSec?: number
}

export interface LoadTestStatus {
  id: string | null
  scenario: LoadTestScenario | null
  phase: LoadTestPhase
  startedAt: string | null
  elapsedSec: number
  currentStage: number
  totalStages: number
  metrics: LoadTestMetrics | null
}

export interface LoadTestMetrics {
  kafkaConsumerLag: number | null
  kafkaProcessedRate: number | null
  esBulkSuccessRate: number | null
  esBulkFailedTotal: number | null
  preferenceUpdateRate: number | null
  recApiP50Ms: number | null
  recApiP95Ms: number | null
  recApiP99Ms: number | null
  notificationTriggered: number | null
  notificationRateLimited: number | null
  jvmHeapUsedBytes: number | null
  redisMemoryUsedBytes: number | null
  totalRequestsSent: number
  totalErrors: number
  avgLatencyMs: number
}

export interface TimestampedMetrics {
  timestamp: string
  elapsedSec: number
  metrics: LoadTestMetrics
}

export interface LoadTestResult {
  id: string
  scenario: LoadTestScenario
  config: LoadTestConfig
  startedAt: string
  completedAt: string
  durationSec: number
  finalMetrics: LoadTestMetrics
  metricsTimeSeries: TimestampedMetrics[]
  note: string
}

export interface LoadTestResultSummary {
  id: string
  scenario: LoadTestScenario
  startedAt: string
  durationSec: number
  note: string
  recApiP95Ms: number | null
  recApiP99Ms: number | null
  kafkaConsumerLag: number | null
  totalErrors: number
  totalRequestsSent: number
  avgLatencyMs: number
}

export interface NoteUpdateRequest {
  note: string
}

// ============================================
// Common Types
// ============================================

export interface ApiError {
  message: string
  status: number
}
