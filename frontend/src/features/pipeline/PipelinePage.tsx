import { useQuery } from '@tanstack/react-query'
import { Activity, Database, Bell, AlertTriangle, CheckCircle, XCircle, Server } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import {
  getAllServiceHealth,
  getBehaviorConsumerMetric,
  getNotificationMetric,
} from '@/api/monitoring'
import type { ServiceHealth, BehaviorConsumerMetrics, NotificationMetrics } from '@/api/types'
import { formatNumber } from '@/lib/utils'

function extractCount(data: { measurements: { statistic: string; value: number }[] }): number {
  const count = data.measurements.find(m => m.statistic === 'COUNT')
  if (count) return count.value
  const value = data.measurements.find(m => m.statistic === 'VALUE')
  if (value) return value.value
  return data.measurements[0]?.value ?? 0
}

async function fetchMetricSafe(
  fetcher: (name: string) => Promise<{ measurements: { statistic: string; value: number }[] }>,
  name: string,
): Promise<number> {
  try {
    const data = await fetcher(name)
    return extractCount(data)
  } catch {
    return 0
  }
}

async function fetchBehaviorConsumerMetrics(): Promise<BehaviorConsumerMetrics> {
  const [eventsProcessed, bulkSuccess, bulkFailed, prefSuccess, prefSkipped, dlqSent] =
    await Promise.all([
      fetchMetricSafe(getBehaviorConsumerMetric, 'kafka.consumer.processed'),
      fetchMetricSafe(getBehaviorConsumerMetric, 'es.bulk.success'),
      fetchMetricSafe(getBehaviorConsumerMetric, 'es.bulk.failed'),
      fetchMetricSafe(getBehaviorConsumerMetric, 'preference.update.success'),
      fetchMetricSafe(getBehaviorConsumerMetric, 'preference.update.skipped'),
      fetchMetricSafe(getBehaviorConsumerMetric, 'kafka.dlq.sent'),
    ])

  return {
    eventsProcessed,
    bulkSuccess,
    bulkFailed,
    preferenceUpdateSuccess: prefSuccess,
    preferenceUpdateSkipped: prefSkipped,
    dlqSent,
  }
}

async function fetchNotificationMetrics(): Promise<NotificationMetrics> {
  const [detected, triggered, rateLimited, sendSuccess, sendFailed, historySuccess] =
    await Promise.all([
      fetchMetricSafe(getNotificationMetric, 'notification.event.detected'),
      fetchMetricSafe(getNotificationMetric, 'notification.triggered'),
      fetchMetricSafe(getNotificationMetric, 'notification.rate.limited'),
      fetchMetricSafe(getNotificationMetric, 'notification.send.success'),
      fetchMetricSafe(getNotificationMetric, 'notification.send.failed'),
      fetchMetricSafe(getNotificationMetric, 'notification.history.save.success'),
    ])

  return {
    eventDetected: detected,
    triggered,
    rateLimited,
    sendSuccess,
    sendFailed,
    historySaveSuccess: historySuccess,
  }
}

function HealthBadge({ status }: { status: ServiceHealth['status'] }) {
  switch (status) {
    case 'UP':
      return (
        <Badge variant="success" className="gap-1">
          <CheckCircle className="h-3 w-3" />
          UP
        </Badge>
      )
    case 'DOWN':
    case 'UNKNOWN':
      return (
        <Badge variant="destructive" className="gap-1">
          <XCircle className="h-3 w-3" />
          {status}
        </Badge>
      )
    default:
      return (
        <Badge variant="outline" className="gap-1">
          <AlertTriangle className="h-3 w-3" />
          OFFLINE
        </Badge>
      )
  }
}

function MetricCard({
  label,
  value,
  isLoading,
  isError,
  variant = 'default',
}: {
  label: string
  value: number
  isLoading: boolean
  isError: boolean
  variant?: 'default' | 'success' | 'error' | 'warning'
}) {
  const colorMap = {
    default: 'text-foreground',
    success: 'text-green-500',
    error: 'text-red-500',
    warning: 'text-yellow-500',
  }

  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      {isLoading ? (
        <Skeleton className="h-7 w-16" />
      ) : isError ? (
        <span className="text-sm text-muted-foreground">-</span>
      ) : (
        <span className={`text-xl font-bold ${colorMap[variant]}`}>
          {formatNumber(value)}
        </span>
      )}
    </div>
  )
}

export function PipelinePage() {
  const healthQuery = useQuery({
    queryKey: ['pipeline', 'health'],
    queryFn: getAllServiceHealth,
    refetchInterval: 3000,
    retry: false,
  })

  const bcQuery = useQuery({
    queryKey: ['pipeline', 'behavior-consumer-metrics'],
    queryFn: fetchBehaviorConsumerMetrics,
    refetchInterval: 3000,
    retry: false,
  })

  const nsQuery = useQuery({
    queryKey: ['pipeline', 'notification-metrics'],
    queryFn: fetchNotificationMetrics,
    refetchInterval: 3000,
    retry: false,
  })

  const services = healthQuery.data ?? []
  const bc = bcQuery.data
  const ns = nsQuery.data

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <div>
        <h2 className="text-2xl font-bold tracking-tight">파이프라인 모니터링</h2>
        <p className="text-muted-foreground">
          Behavior Consumer & Notification Service 실시간 상태
        </p>
      </div>

      {/* 시스템 헬스 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Server className="h-5 w-5" />
            시스템 헬스
          </CardTitle>
          <CardDescription>전체 서비스 상태 (3초 간격 갱신)</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid gap-4 md:grid-cols-4">
            {healthQuery.isLoading ? (
              Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="flex items-center justify-between rounded-lg border p-4">
                  <Skeleton className="h-4 w-24" />
                  <Skeleton className="h-5 w-12" />
                </div>
              ))
            ) : services.length > 0 ? (
              services.map(service => (
                <div
                  key={service.name}
                  className="flex items-center justify-between rounded-lg border p-4"
                >
                  <span className="text-sm font-medium">{service.name}</span>
                  <HealthBadge status={service.status} />
                </div>
              ))
            ) : (
              <div className="col-span-4 text-center text-sm text-muted-foreground py-4">
                서비스 상태를 조회할 수 없습니다
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Behavior Consumer 메트릭 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Database className="h-5 w-5" />
            Behavior Consumer
          </CardTitle>
          <CardDescription>
            Kafka 소비, ES 인덱싱, 취향 벡터 갱신 현황
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid gap-6 md:grid-cols-3 lg:grid-cols-6">
            <MetricCard
              label="Kafka 처리 건수"
              value={bc?.eventsProcessed ?? 0}
              isLoading={bcQuery.isLoading}
              isError={bcQuery.isError}
            />
            <MetricCard
              label="ES 인덱싱 성공"
              value={bc?.bulkSuccess ?? 0}
              isLoading={bcQuery.isLoading}
              isError={bcQuery.isError}
              variant="success"
            />
            <MetricCard
              label="ES 인덱싱 실패"
              value={bc?.bulkFailed ?? 0}
              isLoading={bcQuery.isLoading}
              isError={bcQuery.isError}
              variant={(bc?.bulkFailed ?? 0) > 0 ? 'error' : 'default'}
            />
            <MetricCard
              label="취향 업데이트 성공"
              value={bc?.preferenceUpdateSuccess ?? 0}
              isLoading={bcQuery.isLoading}
              isError={bcQuery.isError}
              variant="success"
            />
            <MetricCard
              label="취향 업데이트 스킵"
              value={bc?.preferenceUpdateSkipped ?? 0}
              isLoading={bcQuery.isLoading}
              isError={bcQuery.isError}
              variant={(bc?.preferenceUpdateSkipped ?? 0) > 0 ? 'warning' : 'default'}
            />
            <MetricCard
              label="DLQ 전송"
              value={bc?.dlqSent ?? 0}
              isLoading={bcQuery.isLoading}
              isError={bcQuery.isError}
              variant={(bc?.dlqSent ?? 0) > 0 ? 'error' : 'default'}
            />
          </div>
        </CardContent>
      </Card>

      {/* Notification Service 메트릭 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Bell className="h-5 w-5" />
            Notification Service
          </CardTitle>
          <CardDescription>
            알림 감지, 발송, Rate Limiting 현황
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid gap-6 md:grid-cols-3 lg:grid-cols-6">
            <MetricCard
              label="이벤트 감지"
              value={ns?.eventDetected ?? 0}
              isLoading={nsQuery.isLoading}
              isError={nsQuery.isError}
            />
            <MetricCard
              label="알림 트리거"
              value={ns?.triggered ?? 0}
              isLoading={nsQuery.isLoading}
              isError={nsQuery.isError}
            />
            <MetricCard
              label="발송 성공"
              value={ns?.sendSuccess ?? 0}
              isLoading={nsQuery.isLoading}
              isError={nsQuery.isError}
              variant="success"
            />
            <MetricCard
              label="발송 실패"
              value={ns?.sendFailed ?? 0}
              isLoading={nsQuery.isLoading}
              isError={nsQuery.isError}
              variant={(ns?.sendFailed ?? 0) > 0 ? 'error' : 'default'}
            />
            <MetricCard
              label="Rate Limit 차단"
              value={ns?.rateLimited ?? 0}
              isLoading={nsQuery.isLoading}
              isError={nsQuery.isError}
              variant={(ns?.rateLimited ?? 0) > 0 ? 'warning' : 'default'}
            />
            <MetricCard
              label="이력 저장 성공"
              value={ns?.historySaveSuccess ?? 0}
              isLoading={nsQuery.isLoading}
              isError={nsQuery.isError}
              variant="success"
            />
          </div>
        </CardContent>
      </Card>

      {/* 안내 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Activity className="h-5 w-5" />
            메트릭 안내
          </CardTitle>
        </CardHeader>
        <CardContent>
          <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
            <li>서비스가 실행 중이 아니면 OFFLINE으로 표시됩니다</li>
            <li>메트릭은 서비스 시작 이후 누적된 카운터 값입니다</li>
            <li>3초마다 자동으로 갱신됩니다</li>
          </ul>
        </CardContent>
      </Card>
    </div>
  )
}
