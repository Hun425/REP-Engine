import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Loader2, RefreshCw, AlertTriangle, AlertCircle, AlertOctagon } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { getAnomalies, triggerAnomalyScan } from '@/api/tracing'
import type { AnomalyType, Severity } from '@/api/types'

const ANOMALY_TYPE_LABELS: Record<AnomalyType, string> = {
  SLOW_TRACE: 'Slow Trace',
  SLOW_SPAN: 'Slow Span',
  ERROR_SPAN: 'Error Span',
  DLQ_ROUTED: 'DLQ Routed',
  HIGH_RETRY: 'High Retry',
}

const SEVERITY_CONFIG: Record<Severity, { color: string; icon: typeof AlertTriangle; variant: 'destructive' | 'default' | 'secondary' }> = {
  CRITICAL: { color: 'text-red-500', icon: AlertOctagon, variant: 'destructive' },
  ERROR: { color: 'text-orange-500', icon: AlertCircle, variant: 'default' },
  WARNING: { color: 'text-yellow-500', icon: AlertTriangle, variant: 'secondary' },
}

interface AnomalyListProps {
  onSelectTrace: (traceId: string) => void
}

export function AnomalyList({ onSelectTrace }: AnomalyListProps) {
  const queryClient = useQueryClient()
  const [typeFilter, setTypeFilter] = useState<AnomalyType | ''>('')

  const { data: anomalies = [], isLoading } = useQuery({
    queryKey: ['anomalies', typeFilter],
    queryFn: () => getAnomalies({
      type: typeFilter || undefined,
      size: 50,
    }),
    refetchInterval: 30000,
  })

  const scanMutation = useMutation({
    mutationFn: triggerAnomalyScan,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['anomalies'] })
    },
  })

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <CardTitle className="text-base">Anomalies</CardTitle>
          <Button
            variant="outline"
            size="sm"
            onClick={() => scanMutation.mutate()}
            disabled={scanMutation.isPending}
          >
            {scanMutation.isPending ? (
              <Loader2 className="h-4 w-4 mr-1 animate-spin" />
            ) : (
              <RefreshCw className="h-4 w-4 mr-1" />
            )}
            Scan Now
          </Button>
        </div>
        {scanMutation.isSuccess && scanMutation.data && (
          <p className="text-xs text-muted-foreground">
            Scanned {scanMutation.data.totalScanned} traces, found {scanMutation.data.newAnomalies} new anomalies
            ({scanMutation.data.scanDurationMs}ms)
          </p>
        )}
      </CardHeader>
      <CardContent>
        {/* Type filter */}
        <div className="flex gap-2 mb-4 flex-wrap">
          <Badge
            variant={typeFilter === '' ? 'default' : 'outline'}
            className="cursor-pointer"
            onClick={() => setTypeFilter('')}
          >
            All
          </Badge>
          {(Object.keys(ANOMALY_TYPE_LABELS) as AnomalyType[]).map(type => (
            <Badge
              key={type}
              variant={typeFilter === type ? 'default' : 'outline'}
              className="cursor-pointer"
              onClick={() => setTypeFilter(type)}
            >
              {ANOMALY_TYPE_LABELS[type]}
            </Badge>
          ))}
        </div>

        {isLoading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : anomalies.length === 0 ? (
          <p className="text-center py-8 text-muted-foreground text-sm">No anomalies detected</p>
        ) : (
          <div className="space-y-2">
            {anomalies.map(anomaly => {
              const config = SEVERITY_CONFIG[anomaly.severity]
              const Icon = config.icon

              return (
                <div
                  key={anomaly.id}
                  className="flex items-center gap-3 p-3 border rounded-lg cursor-pointer hover:bg-muted/30 transition-colors"
                  onClick={() => onSelectTrace(anomaly.traceId)}
                >
                  <Icon className={`h-4 w-4 shrink-0 ${config.color}`} />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <Badge variant={config.variant} className="text-[10px] px-1.5 py-0">
                        {anomaly.severity}
                      </Badge>
                      <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                        {ANOMALY_TYPE_LABELS[anomaly.type]}
                      </Badge>
                      <span className="text-xs text-muted-foreground">
                        {anomaly.serviceName}
                      </span>
                    </div>
                    <p className="text-sm mt-0.5 truncate">
                      {anomaly.operationName}
                      <span className="text-muted-foreground ml-2">
                        {anomaly.durationMs}ms
                        {anomaly.thresholdMs && ` (threshold: ${anomaly.thresholdMs}ms)`}
                      </span>
                    </p>
                    {anomaly.errorMessage && (
                      <p className="text-xs text-red-400 mt-0.5 truncate">{anomaly.errorMessage}</p>
                    )}
                  </div>
                  <span className="text-xs text-muted-foreground shrink-0">
                    {new Date(anomaly.detectedAt).toLocaleTimeString()}
                  </span>
                </div>
              )
            })}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
