import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Play, Square, Loader2, RefreshCw } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { getSimulatorStatus, startSimulator, stopSimulator } from '@/api/simulator'
import { formatNumber } from '@/lib/utils'

export function SimulatorPage() {
  const queryClient = useQueryClient()
  const [userCount, setUserCount] = useState(100)
  const [delayMillis, setDelayMillis] = useState(1000)

  // 상태 조회
  const statusQuery = useQuery({
    queryKey: ['simulator', 'status'],
    queryFn: getSimulatorStatus,
    refetchInterval: 2000, // 2초마다 갱신
    retry: false,
  })

  // 시작 뮤테이션
  const startMutation = useMutation({
    mutationFn: () => startSimulator({ userCount, delayMillis }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['simulator'] })
    },
  })

  // 정지 뮤테이션
  const stopMutation = useMutation({
    mutationFn: stopSimulator,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['simulator'] })
    },
  })

  const status = statusQuery.data
  const isRunning = status?.isRunning ?? false
  const isLoading = startMutation.isPending || stopMutation.isPending

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">시뮬레이터 제어</h2>
          <p className="text-muted-foreground">
            트래픽 시뮬레이터를 시작/정지하고 상태를 확인하세요
          </p>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => statusQuery.refetch()}
          disabled={statusQuery.isFetching}
        >
          <RefreshCw className={`h-4 w-4 mr-2 ${statusQuery.isFetching ? 'animate-spin' : ''}`} />
          새로고침
        </Button>
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        {/* 제어판 */}
        <Card>
          <CardHeader>
            <CardTitle>제어판</CardTitle>
            <CardDescription>시뮬레이터 설정 및 제어</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {/* 상태 표시 */}
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium">상태:</span>
              {statusQuery.isError ? (
                <Badge variant="destructive">연결 실패</Badge>
              ) : isRunning ? (
                <Badge variant="success">● 실행 중</Badge>
              ) : (
                <Badge variant="secondary">○ 정지됨</Badge>
              )}
            </div>

            {/* 설정 입력 */}
            <div className="space-y-3">
              <div>
                <label className="text-sm font-medium">유저 수</label>
                <Input
                  type="number"
                  value={userCount}
                  onChange={(e) => setUserCount(Number(e.target.value))}
                  min={1}
                  max={10000}
                  disabled={isRunning}
                />
              </div>
              <div>
                <label className="text-sm font-medium">이벤트 간격 (ms)</label>
                <Input
                  type="number"
                  value={delayMillis}
                  onChange={(e) => setDelayMillis(Number(e.target.value))}
                  min={100}
                  max={10000}
                  disabled={isRunning}
                />
              </div>
            </div>

            {/* 제어 버튼 */}
            <div className="flex gap-2">
              {isRunning ? (
                <Button
                  variant="destructive"
                  onClick={() => stopMutation.mutate()}
                  disabled={isLoading}
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
                  disabled={isLoading || statusQuery.isError}
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

            {/* 에러 메시지 */}
            {statusQuery.isError && (
              <p className="text-sm text-destructive">
                시뮬레이터 API에 연결할 수 없습니다. 백엔드가 실행 중인지 확인하세요.
              </p>
            )}
            {(startMutation.isError || stopMutation.isError) && (
              <p className="text-sm text-destructive">
                작업에 실패했습니다. 다시 시도해주세요.
              </p>
            )}
          </CardContent>
        </Card>

        {/* 현재 상태 */}
        <Card>
          <CardHeader>
            <CardTitle>현재 상태</CardTitle>
            <CardDescription>실시간 시뮬레이터 통계</CardDescription>
          </CardHeader>
          <CardContent>
            {statusQuery.isError ? (
              <p className="text-muted-foreground">데이터를 불러올 수 없습니다</p>
            ) : status ? (
              <div className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <p className="text-sm text-muted-foreground">총 발송 이벤트</p>
                    <p className="text-2xl font-bold">{formatNumber(status.totalEventsSent)}</p>
                  </div>
                  <div>
                    <p className="text-sm text-muted-foreground">활성 유저</p>
                    <p className="text-2xl font-bold">{formatNumber(status.userCount)}</p>
                  </div>
                  <div>
                    <p className="text-sm text-muted-foreground">이벤트 간격</p>
                    <p className="text-2xl font-bold">{status.delayMillis}ms</p>
                  </div>
                  <div>
                    <p className="text-sm text-muted-foreground">예상 초당 이벤트</p>
                    <p className="text-2xl font-bold">
                      ~{Math.round(status.userCount / (status.delayMillis / 1000))}
                    </p>
                  </div>
                </div>
              </div>
            ) : (
              <p className="text-muted-foreground">로딩 중...</p>
            )}
          </CardContent>
        </Card>
      </div>

      {/* 가이드 */}
      <Card>
        <CardHeader>
          <CardTitle>사용 가이드</CardTitle>
        </CardHeader>
        <CardContent>
          <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
            <li>시뮬레이터는 가상 유저들이 상품을 조회, 클릭, 구매하는 이벤트를 생성합니다</li>
            <li>이벤트는 Kafka로 전송되어 behavior-consumer에서 처리됩니다</li>
            <li>유저 취향 벡터가 갱신되면 추천 API에서 개인화된 결과를 받을 수 있습니다</li>
            <li>시뮬레이터 API가 없다면 백엔드에 SimulatorController를 추가해야 합니다</li>
          </ul>
        </CardContent>
      </Card>
    </div>
  )
}
