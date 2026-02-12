import axios from 'axios'

// API 클라이언트 인스턴스
export const apiClient = axios.create({
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Request 인터셉터 (로깅, 인증 토큰 추가 등)
apiClient.interceptors.request.use(
  (config) => {
    // 개발 환경에서 요청 로깅
    if (import.meta.env.DEV) {
      console.log(`[API] ${config.method?.toUpperCase()} ${config.url}`)
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// Response 인터셉터 (에러 핸들링)
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      // 서버 응답 에러
      console.error(`[API Error] ${error.response.status}: ${error.response.data?.message || error.message}`)
    } else if (error.request) {
      // 네트워크 에러
      console.error('[API Error] Network error - no response received')
    } else {
      console.error('[API Error]', error.message)
    }
    return Promise.reject(error)
  }
)

// 환경별 Base URL
export const API_URLS = {
  recommendation: import.meta.env.VITE_RECOMMENDATION_API_URL || '/api/v1/recommendations',
  simulator: import.meta.env.VITE_SIMULATOR_API_URL || '/api/v1/simulator',
  simulatorActuator: '/actuator/simulator',
  behaviorConsumer: '/actuator/behavior-consumer',
  recommendationActuator: '/actuator/recommendation',
  notification: '/actuator/notification',
  loadTest: '/api/v1/load-test',
  tracing: '/api/v1/tracing',
  grafana: import.meta.env.VITE_GRAFANA_URL || 'http://localhost:3000',
}
