import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Play, Square, Loader2, RefreshCw, Trash2, FileText, GitCompare,
  ArrowUp, ArrowDown, Minus, X
} from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import {
  startLoadTest, getLoadTestStatus, stopLoadTest,
  getLoadTestResults, getLoadTestResult, deleteLoadTestResult, updateLoadTestNote
} from '@/api/loadtest'
import { formatNumber } from '@/lib/utils'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer
} from 'recharts'
import type {
  LoadTestScenario, LoadTestConfig, LoadTestMetrics,
  LoadTestResultSummary, TimestampedMetrics
} from '@/api/types'

const SCENARIO_LABELS: Record<LoadTestScenario, string> = {
  PIPELINE_STRESS: 'Pipeline Stress',
  RECOMMENDATION_LOAD: 'Recommendation Load',
  NOTIFICATION_LOAD: 'Notification Load',
}

const PHASE_BADGE: Record<string, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'success' | 'outline' }> = {
  NOT_STARTED: { label: 'Ready', variant: 'secondary' },
  RUNNING: { label: 'Running', variant: 'success' },
  STOPPING: { label: 'Stopping', variant: 'default' },
  COMPLETED: { label: 'Completed', variant: 'outline' },
  FAILED: { label: 'Failed', variant: 'destructive' },
}

function formatMetric(value: number | null | undefined, unit = ''): string {
  if (value == null || isNaN(value)) return 'N/A'
  if (unit === 'bytes') {
    if (value > 1024 * 1024 * 1024) return `${(value / (1024 * 1024 * 1024)).toFixed(1)} GB`
    if (value > 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`
    return `${(value / 1024).toFixed(1)} KB`
  }
  if (unit === 'ms') return `${value.toFixed(1)} ms`
  if (unit === '/s') return `${value.toFixed(1)} /s`
  return formatNumber(Math.round(value))
}

export function LoadTestPage() {
  const queryClient = useQueryClient()

  // === Config State ===
  const [scenario, setScenario] = useState<LoadTestScenario>('PIPELINE_STRESS')
  const [delayMillis, setDelayMillis] = useState(500)
  const [concurrentUsers, setConcurrentUsers] = useState(10)
  const [durationSec, setDurationSec] = useState(60)
  const [requestIntervalMs, setRequestIntervalMs] = useState(200)
  const [inventoryEnabled, setInventoryEnabled] = useState(true)

  // === Result Detail & Compare ===
  const [selectedResultId, setSelectedResultId] = useState<string | null>(null)
  const [compareIds, setCompareIds] = useState<string[]>([])
  const [editingNoteId, setEditingNoteId] = useState<string | null>(null)
  const [noteText, setNoteText] = useState('')

  // === Queries ===
  const statusQuery = useQuery({
    queryKey: ['loadtest', 'status'],
    queryFn: getLoadTestStatus,
    refetchInterval: (query) => {
      const phase = query.state.data?.phase
      return phase === 'RUNNING' || phase === 'STOPPING' ? 2000 : false
    },
    retry: false,
  })

  const resultsQuery = useQuery({
    queryKey: ['loadtest', 'results'],
    queryFn: getLoadTestResults,
    refetchInterval: 10000,
  })

  const resultDetailQuery = useQuery({
    queryKey: ['loadtest', 'result', selectedResultId],
    queryFn: () => getLoadTestResult(selectedResultId!),
    enabled: !!selectedResultId,
  })

  // Compare queries
  const compare0Query = useQuery({
    queryKey: ['loadtest', 'result', compareIds[0]],
    queryFn: () => getLoadTestResult(compareIds[0]),
    enabled: compareIds.length === 2 && !!compareIds[0],
  })
  const compare1Query = useQuery({
    queryKey: ['loadtest', 'result', compareIds[1]],
    queryFn: () => getLoadTestResult(compareIds[1]),
    enabled: compareIds.length === 2 && !!compareIds[1],
  })

  // === Mutations ===
  const startMutation = useMutation({
    mutationFn: () => {
      const config: LoadTestConfig = {
        delayMillis,
        concurrentUsers,
        durationSec,
        requestIntervalMs,
        inventoryEnabled,
      }
      return startLoadTest({ scenario, config })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['loadtest'] })
    },
  })

  const stopMutation = useMutation({
    mutationFn: stopLoadTest,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['loadtest'] })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: deleteLoadTestResult,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['loadtest', 'results'] })
      if (selectedResultId) setSelectedResultId(null)
    },
  })

  const noteMutation = useMutation({
    mutationFn: ({ id, note }: { id: string; note: string }) => updateLoadTestNote(id, note),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['loadtest', 'results'] })
      setEditingNoteId(null)
    },
  })

  const status = statusQuery.data
  const phase = status?.phase ?? 'NOT_STARTED'
  const isRunning = phase === 'RUNNING' || phase === 'STOPPING'
  const phaseInfo = PHASE_BADGE[phase] ?? PHASE_BADGE.NOT_STARTED

  // Chart data from live status or result detail
  const chartData = useMemo(() => {
    const series = resultDetailQuery.data?.metricsTimeSeries
    if (series && series.length > 0) return series
    return null
  }, [resultDetailQuery.data])

  // === Toggle compare checkbox ===
  function toggleCompare(id: string) {
    setCompareIds(prev => {
      if (prev.includes(id)) return prev.filter(x => x !== id)
      if (prev.length >= 2) return [prev[1], id]
      return [...prev, id]
    })
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Load Test</h2>
          <p className="text-muted-foreground">
            부하 테스트 실행, 실시간 모니터링, 결과 비교
          </p>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            statusQuery.refetch()
            resultsQuery.refetch()
          }}
          disabled={statusQuery.isFetching}
        >
          <RefreshCw className={`h-4 w-4 mr-2 ${statusQuery.isFetching ? 'animate-spin' : ''}`} />
          새로고침
        </Button>
      </div>

      {/* Section 1: Control + Status */}
      <div className="grid gap-6 md:grid-cols-2">
        {/* Control Panel */}
        <Card>
          <CardHeader>
            <CardTitle>테스트 설정</CardTitle>
            <CardDescription>시나리오 선택 및 파라미터 설정</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div>
              <label className="text-sm font-medium">시나리오</label>
              <select
                className="w-full mt-1 rounded-md border border-input bg-background px-3 py-2 text-sm"
                value={scenario}
                onChange={(e) => setScenario(e.target.value as LoadTestScenario)}
                disabled={isRunning}
              >
                {Object.entries(SCENARIO_LABELS).map(([key, label]) => (
                  <option key={key} value={key}>{label}</option>
                ))}
              </select>
            </div>

            {/* Scenario-specific config */}
            {scenario === 'PIPELINE_STRESS' && (
              <div>
                <label className="text-sm font-medium">Delay (ms)</label>
                <Input
                  type="number"
                  value={delayMillis}
                  onChange={(e) => setDelayMillis(Number(e.target.value))}
                  min={50}
                  max={5000}
                  disabled={isRunning}
                />
                <p className="text-xs text-muted-foreground mt-1">
                  5단계 프리셋: 100→300→500→800→1000 유저
                </p>
              </div>
            )}

            {scenario === 'RECOMMENDATION_LOAD' && (
              <div className="space-y-3">
                <div>
                  <label className="text-sm font-medium">동시 유저 수</label>
                  <Input
                    type="number"
                    value={concurrentUsers}
                    onChange={(e) => setConcurrentUsers(Number(e.target.value))}
                    min={1}
                    max={500}
                    disabled={isRunning}
                  />
                </div>
                <div>
                  <label className="text-sm font-medium">테스트 시간 (초)</label>
                  <Input
                    type="number"
                    value={durationSec}
                    onChange={(e) => setDurationSec(Number(e.target.value))}
                    min={10}
                    max={600}
                    disabled={isRunning}
                  />
                </div>
                <div>
                  <label className="text-sm font-medium">요청 간격 (ms)</label>
                  <Input
                    type="number"
                    value={requestIntervalMs}
                    onChange={(e) => setRequestIntervalMs(Number(e.target.value))}
                    min={50}
                    max={5000}
                    disabled={isRunning}
                  />
                </div>
              </div>
            )}

            {scenario === 'NOTIFICATION_LOAD' && (
              <div className="space-y-3">
                <label className="flex items-center gap-2 text-sm font-medium cursor-pointer">
                  <input
                    type="checkbox"
                    checked={inventoryEnabled}
                    onChange={(e) => setInventoryEnabled(e.target.checked)}
                    disabled={isRunning}
                    className="rounded"
                  />
                  Inventory Simulator 포함
                </label>
                <p className="text-xs text-muted-foreground">
                  4단계: Traffic 워밍업 → +Inventory +Rec → Inventory만 → 쿨다운
                </p>
              </div>
            )}

            {/* Start / Stop */}
            <div className="flex gap-2 pt-2">
              {isRunning ? (
                <Button
                  variant="destructive"
                  onClick={() => stopMutation.mutate()}
                  disabled={stopMutation.isPending || phase === 'STOPPING'}
                  className="flex-1"
                >
                  {stopMutation.isPending ? (
                    <Loader2 className="h-4 w-4 animate-spin mr-2" />
                  ) : (
                    <Square className="h-4 w-4 mr-2" />
                  )}
                  정지
                </Button>
              ) : (
                <Button
                  onClick={() => startMutation.mutate()}
                  disabled={startMutation.isPending || statusQuery.isError}
                  className="flex-1"
                >
                  {startMutation.isPending ? (
                    <Loader2 className="h-4 w-4 animate-spin mr-2" />
                  ) : (
                    <Play className="h-4 w-4 mr-2" />
                  )}
                  시작
                </Button>
              )}
            </div>

            {statusQuery.isError && (
              <p className="text-sm text-destructive">
                Simulator API에 연결할 수 없습니다.
              </p>
            )}
            {startMutation.isError && (
              <p className="text-sm text-destructive">
                테스트 시작 실패. 이미 실행 중인 테스트가 있을 수 있습니다.
              </p>
            )}
          </CardContent>
        </Card>

        {/* Status Panel */}
        <Card>
          <CardHeader>
            <CardTitle>테스트 상태</CardTitle>
            <CardDescription>현재 실행 정보</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium">상태:</span>
              <Badge variant={phaseInfo.variant}>{phaseInfo.label}</Badge>
            </div>

            {status && status.id && (
              <div className="space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">테스트 ID</span>
                  <span className="font-mono">{status.id}</span>
                </div>
                {status.scenario && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">시나리오</span>
                    <span>{SCENARIO_LABELS[status.scenario]}</span>
                  </div>
                )}
                <div className="flex justify-between">
                  <span className="text-muted-foreground">경과 시간</span>
                  <span>{status.elapsedSec}초</span>
                </div>
                {status.totalStages > 0 && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">스테이지</span>
                    <span>{status.currentStage} / {status.totalStages}</span>
                  </div>
                )}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Live Metrics Grid (only when running) */}
      {isRunning && status?.metrics && (
        <Card>
          <CardHeader>
            <CardTitle>실시간 메트릭</CardTitle>
          </CardHeader>
          <CardContent>
            <MetricsGrid metrics={status.metrics} />
          </CardContent>
        </Card>
      )}

      {/* Section 2: Chart (when viewing a result detail) */}
      {chartData && chartData.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center justify-between">
              <span>메트릭 차트</span>
              {selectedResultId && (
                <Button variant="outline" size="sm" onClick={() => setSelectedResultId(null)}>
                  <X className="h-4 w-4 mr-1" /> 닫기
                </Button>
              )}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <MetricsChart data={chartData} />
          </CardContent>
        </Card>
      )}

      {/* Compare View */}
      {compareIds.length === 2 && compare0Query.data && compare1Query.data && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center justify-between">
              <span>결과 비교</span>
              <Button variant="outline" size="sm" onClick={() => setCompareIds([])}>
                <X className="h-4 w-4 mr-1" /> 닫기
              </Button>
            </CardTitle>
          </CardHeader>
          <CardContent>
            <CompareView
              a={compare0Query.data}
              b={compare1Query.data}
            />
          </CardContent>
        </Card>
      )}

      {/* Section 3: Results History */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between">
            <span>결과 히스토리</span>
            {compareIds.length === 2 && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => {/* compare view is shown above */}}
                disabled={!compare0Query.data || !compare1Query.data}
              >
                <GitCompare className="h-4 w-4 mr-2" />
                비교 중 ({compareIds.length}/2)
              </Button>
            )}
          </CardTitle>
          <CardDescription>
            체크박스로 2개 선택 후 비교 가능
          </CardDescription>
        </CardHeader>
        <CardContent>
          {resultsQuery.data && resultsQuery.data.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="p-2 w-8"></th>
                    <th className="p-2">ID</th>
                    <th className="p-2">시나리오</th>
                    <th className="p-2">시작 시간</th>
                    <th className="p-2 text-right">소요(초)</th>
                    <th className="p-2 text-right">P95</th>
                    <th className="p-2 text-right">P99</th>
                    <th className="p-2 text-right">Lag</th>
                    <th className="p-2 text-right">에러</th>
                    <th className="p-2">메모</th>
                    <th className="p-2">액션</th>
                  </tr>
                </thead>
                <tbody>
                  {resultsQuery.data.map((r: LoadTestResultSummary) => (
                    <tr key={r.id} className="border-b hover:bg-muted/50">
                      <td className="p-2">
                        <input
                          type="checkbox"
                          checked={compareIds.includes(r.id)}
                          onChange={() => toggleCompare(r.id)}
                          className="rounded"
                        />
                      </td>
                      <td className="p-2 font-mono text-xs">{r.id.substring(3, 16)}</td>
                      <td className="p-2">
                        <Badge variant="outline">{SCENARIO_LABELS[r.scenario]}</Badge>
                      </td>
                      <td className="p-2 text-xs">
                        {new Date(r.startedAt).toLocaleString('ko-KR')}
                      </td>
                      <td className="p-2 text-right">{r.durationSec}</td>
                      <td className="p-2 text-right">{formatMetric(r.recApiP95Ms, 'ms')}</td>
                      <td className="p-2 text-right">{formatMetric(r.recApiP99Ms, 'ms')}</td>
                      <td className="p-2 text-right">{formatMetric(r.kafkaConsumerLag)}</td>
                      <td className="p-2 text-right">{formatNumber(r.totalErrors)}</td>
                      <td className="p-2">
                        {editingNoteId === r.id ? (
                          <div className="flex gap-1">
                            <Input
                              value={noteText}
                              onChange={(e) => setNoteText(e.target.value)}
                              className="h-7 text-xs"
                              onKeyDown={(e) => {
                                if (e.key === 'Enter') {
                                  noteMutation.mutate({ id: r.id, note: noteText })
                                }
                                if (e.key === 'Escape') setEditingNoteId(null)
                              }}
                            />
                            <Button
                              size="sm"
                              variant="outline"
                              className="h-7 px-2"
                              onClick={() => noteMutation.mutate({ id: r.id, note: noteText })}
                            >
                              OK
                            </Button>
                          </div>
                        ) : (
                          <span
                            className="text-xs text-muted-foreground cursor-pointer hover:text-foreground"
                            onClick={() => {
                              setEditingNoteId(r.id)
                              setNoteText(r.note || '')
                            }}
                          >
                            {r.note || '(클릭하여 메모)'}
                          </span>
                        )}
                      </td>
                      <td className="p-2">
                        <div className="flex gap-1">
                          <Button
                            size="sm"
                            variant="outline"
                            className="h-7 px-2"
                            onClick={() => setSelectedResultId(r.id)}
                          >
                            <FileText className="h-3 w-3" />
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            className="h-7 px-2 text-destructive"
                            onClick={() => deleteMutation.mutate(r.id)}
                          >
                            <Trash2 className="h-3 w-3" />
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-muted-foreground text-sm">결과가 없습니다. 테스트를 실행해주세요.</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

// === Sub-components ===

function MetricsGrid({ metrics }: { metrics: LoadTestMetrics }) {
  const items = [
    { label: 'Consumer Lag', value: formatMetric(metrics.kafkaConsumerLag) },
    { label: '처리율', value: formatMetric(metrics.kafkaProcessedRate, '/s') },
    { label: 'ES Bulk', value: formatMetric(metrics.esBulkSuccessRate, '/s') },
    { label: 'API P95', value: formatMetric(metrics.recApiP95Ms, 'ms') },
    { label: 'API P99', value: formatMetric(metrics.recApiP99Ms, 'ms') },
    { label: '총 요청', value: formatNumber(metrics.totalRequestsSent) },
    { label: '에러', value: formatNumber(metrics.totalErrors) },
    { label: 'Avg Latency', value: formatMetric(metrics.avgLatencyMs, 'ms') },
    { label: 'JVM Heap', value: formatMetric(metrics.jvmHeapUsedBytes, 'bytes') },
    { label: 'Redis Memory', value: formatMetric(metrics.redisMemoryUsedBytes, 'bytes') },
  ]

  return (
    <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
      {items.map((item) => (
        <div key={item.label} className="text-center p-3 rounded-lg bg-muted/50">
          <p className="text-xs text-muted-foreground">{item.label}</p>
          <p className="text-lg font-bold mt-1">{item.value}</p>
        </div>
      ))}
    </div>
  )
}

function MetricsChart({ data }: { data: TimestampedMetrics[] }) {
  const chartData = data.map(d => ({
    time: d.elapsedSec,
    p95: d.metrics.recApiP95Ms ?? 0,
    lag: d.metrics.kafkaConsumerLag ?? 0,
    processRate: d.metrics.kafkaProcessedRate ?? 0,
  }))

  return (
    <ResponsiveContainer width="100%" height={300}>
      <LineChart data={chartData}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis
          dataKey="time"
          label={{ value: '경과 시간(초)', position: 'insideBottom', offset: -5 }}
        />
        <YAxis yAxisId="left" label={{ value: 'ms / count', angle: -90, position: 'insideLeft' }} />
        <YAxis yAxisId="right" orientation="right" label={{ value: '/s', angle: 90, position: 'insideRight' }} />
        <Tooltip />
        <Legend />
        <Line yAxisId="left" type="monotone" dataKey="p95" stroke="#ef4444" name="P95 (ms)" dot={false} />
        <Line yAxisId="left" type="monotone" dataKey="lag" stroke="#f59e0b" name="Consumer Lag" dot={false} />
        <Line yAxisId="right" type="monotone" dataKey="processRate" stroke="#22c55e" name="처리율 (/s)" dot={false} />
      </LineChart>
    </ResponsiveContainer>
  )
}

interface CompareViewProps {
  a: { id: string; scenario: LoadTestScenario; finalMetrics: LoadTestMetrics; durationSec: number }
  b: { id: string; scenario: LoadTestScenario; finalMetrics: LoadTestMetrics; durationSec: number }
}

function CompareView({ a, b }: CompareViewProps) {
  const rows: { label: string; unit: string; getVal: (m: LoadTestMetrics) => number | null; lowerIsBetter: boolean }[] = [
    { label: 'API P95', unit: 'ms', getVal: m => m.recApiP95Ms, lowerIsBetter: true },
    { label: 'API P99', unit: 'ms', getVal: m => m.recApiP99Ms, lowerIsBetter: true },
    { label: 'Consumer Lag', unit: '', getVal: m => m.kafkaConsumerLag, lowerIsBetter: true },
    { label: 'Avg Latency', unit: 'ms', getVal: m => m.avgLatencyMs, lowerIsBetter: true },
    { label: '총 요청', unit: '', getVal: m => m.totalRequestsSent, lowerIsBetter: false },
    { label: '총 에러', unit: '', getVal: m => m.totalErrors, lowerIsBetter: true },
    { label: '처리율', unit: '/s', getVal: m => m.kafkaProcessedRate, lowerIsBetter: false },
    { label: 'ES Bulk', unit: '/s', getVal: m => m.esBulkSuccessRate, lowerIsBetter: false },
  ]

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left">
            <th className="p-2">메트릭</th>
            <th className="p-2 text-right">{a.id.substring(3, 16)}</th>
            <th className="p-2 text-right">{b.id.substring(3, 16)}</th>
            <th className="p-2 text-center">Delta</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({ label, unit, getVal, lowerIsBetter }) => {
            const valA = getVal(a.finalMetrics)
            const valB = getVal(b.finalMetrics)
            const delta = (valA != null && valB != null) ? valB - valA : null
            const improved = delta != null && (lowerIsBetter ? delta < 0 : delta > 0)
            const worsened = delta != null && (lowerIsBetter ? delta > 0 : delta < 0)

            return (
              <tr key={label} className="border-b">
                <td className="p-2 font-medium">{label}</td>
                <td className="p-2 text-right">{formatMetric(valA, unit)}</td>
                <td className="p-2 text-right">{formatMetric(valB, unit)}</td>
                <td className="p-2 text-center">
                  {delta == null ? (
                    <span className="text-muted-foreground">N/A</span>
                  ) : delta === 0 ? (
                    <span className="text-muted-foreground flex items-center justify-center gap-1">
                      <Minus className="h-3 w-3" /> 0
                    </span>
                  ) : (
                    <span className={`flex items-center justify-center gap-1 ${improved ? 'text-green-600' : worsened ? 'text-red-600' : ''}`}>
                      {delta > 0 ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" />}
                      {formatMetric(Math.abs(delta), unit)}
                    </span>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
