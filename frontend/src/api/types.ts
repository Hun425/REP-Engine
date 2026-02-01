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
  isRunning: boolean
  totalEventsSent: number
  userCount: number
  delayMillis: number
}

export interface SimulatorStartRequest {
  userCount?: number
  delayMillis?: number
}

// ============================================
// Common Types
// ============================================

export interface ApiError {
  message: string
  status: number
}
