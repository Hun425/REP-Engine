import { apiClient, API_URLS } from './client'
import type { RecommendationResponse, RecommendationParams } from './types'

/**
 * 개인화 추천 상품 조회
 */
export async function getRecommendations(params: RecommendationParams): Promise<RecommendationResponse> {
  const { userId, limit = 10, category, excludeViewed = true } = params

  const response = await apiClient.get<RecommendationResponse>(
    `${API_URLS.recommendation}/${userId}`,
    {
      params: {
        limit,
        category,
        excludeViewed,
      },
    }
  )

  return response.data
}

/**
 * 인기 상품 조회 (Cold Start 대응)
 */
export async function getPopularProducts(
  limit = 10,
  category?: string
): Promise<RecommendationResponse> {
  const response = await apiClient.get<RecommendationResponse>(
    `${API_URLS.recommendation}/popular`,
    {
      params: { limit, category },
    }
  )

  return response.data
}

/**
 * 추천 API 헬스체크
 */
export async function checkRecommendationHealth(): Promise<boolean> {
  try {
    const response = await apiClient.get(`${API_URLS.recommendation}/health`)
    return response.data?.status === 'ok'
  } catch {
    return false
  }
}
