import { apiClient, API_URLS } from './client'
import type { ActuatorHealth, ActuatorMetricResponse, ServiceHealth } from './types'

export async function getBehaviorConsumerHealth(): Promise<ActuatorHealth> {
  const response = await apiClient.get<ActuatorHealth>(
    `${API_URLS.behaviorConsumer}/health`
  )
  return response.data
}

export async function getBehaviorConsumerMetric(
  name: string
): Promise<ActuatorMetricResponse> {
  const response = await apiClient.get<ActuatorMetricResponse>(
    `${API_URLS.behaviorConsumer}/metrics/${name}`
  )
  return response.data
}

export async function getNotificationHealth(): Promise<ActuatorHealth> {
  const response = await apiClient.get<ActuatorHealth>(
    `${API_URLS.notification}/health`
  )
  return response.data
}

export async function getNotificationMetric(
  name: string
): Promise<ActuatorMetricResponse> {
  const response = await apiClient.get<ActuatorMetricResponse>(
    `${API_URLS.notification}/metrics/${name}`
  )
  return response.data
}

export async function getAllServiceHealth(): Promise<ServiceHealth[]> {
  const services: { name: string; fetcher: () => Promise<ActuatorHealth> }[] = [
    {
      name: 'Simulator',
      fetcher: () => apiClient.get<ActuatorHealth>(`${API_URLS.simulatorActuator}/health`).then(r => r.data),
    },
    {
      name: 'Behavior Consumer',
      fetcher: getBehaviorConsumerHealth,
    },
    {
      name: 'Recommendation API',
      fetcher: () => apiClient.get<ActuatorHealth>(`${API_URLS.recommendationActuator}/health`).then(r => r.data),
    },
    {
      name: 'Notification Service',
      fetcher: getNotificationHealth,
    },
  ]

  const results = await Promise.allSettled(services.map(s => s.fetcher()))

  return results.map((result, i) => ({
    name: services[i].name,
    status: result.status === 'fulfilled' ? result.value.status : 'OFFLINE' as const,
  }))
}
