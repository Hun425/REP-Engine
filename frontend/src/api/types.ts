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
// Common Types
// ============================================

export interface ApiError {
  message: string
  status: number
}
