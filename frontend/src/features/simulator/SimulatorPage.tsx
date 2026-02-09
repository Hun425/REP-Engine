import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Play, Square, Loader2, RefreshCw, Package } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { getSimulatorStatus, startSimulator, stopSimulator, getInventoryStatus, startInventorySimulator, stopInventorySimulator } from '@/api/simulator'
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

  // === Inventory Simulator ===
  const inventoryStatusQuery = useQuery({
    queryKey: ['simulator', 'inventory', 'status'],
    queryFn: getInventoryStatus,
    refetchInterval: 2000,
    retry: false,
  })

  const startInventoryMutation = useMutation({
    mutationFn: startInventorySimulator,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['simulator', 'inventory'] })
    },
  })

  const stopInventoryMutation = useMutation({
    mutationFn: stopInventorySimulator,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['simulator', 'inventory'] })
    },
  })

  const status = statusQuery.data
  const isRunning = status?.running ?? false
  const isLoading = startMutation.isPending || stopMutation.isPending

  const inventoryStatus = inventoryStatusQuery.data
  const isInventoryRunning = inventoryStatus?.running ?? false
  const isInventoryLoading = startInventoryMutation.isPending || stopInventoryMutation.isPending

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

      {/* 인벤토리 시뮬레이터 */}
      <div className="grid gap-6 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Package className="h-5 w-5" />
              인벤토리 시뮬레이터
            </CardTitle>
            <CardDescription>가격 변동 / 재입고 이벤트 생성 (Notification Service 연동)</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium">상태:</span>
              {inventoryStatusQuery.isError ? (
                <Badge variant="destructive">연결 실패</Badge>
              ) : isInventoryRunning ? (
                <Badge variant="success">● 실행 중</Badge>
              ) : (
                <Badge variant="secondary">○ 정지됨</Badge>
              )}
            </div>

            <div className="flex gap-2">
              {isInventoryRunning ? (
                <Button
                  variant="destructive"
                  onClick={() => stopInventoryMutation.mutate()}
                  disabled={isInventoryLoading}
                  className="flex-1"
                >
                  {stopInventoryMutation.isPending ? (
                    <Loader2 className="h-4 w-4 animate-spin mr-2" />
                  ) : (
                    <Square className="h-4 w-4 mr-2" />
                  )}
                  정지
                </Button>
              ) : (
                <Button
                  onClick={() => startInventoryMutation.mutate()}
                  disabled={isInventoryLoading || inventoryStatusQuery.isError}
                  className="flex-1"
                >
                  {startInventoryMutation.isPending ? (
                    <Loader2 className="h-4 w-4 animate-spin mr-2" />
                  ) : (
                    <Play className="h-4 w-4 mr-2" />
                  )}
                  시작
                </Button>
              )}
            </div>

            {(startInventoryMutation.isError || stopInventoryMutation.isError) && (
              <p className="text-sm text-destructive">
                작업에 실패했습니다. 다시 시도해주세요.
              </p>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>인벤토리 상태</CardTitle>
            <CardDescription>가격/재고 변동 이벤트 통계</CardDescription>
          </CardHeader>
          <CardContent>
            {inventoryStatusQuery.isError ? (
              <p className="text-muted-foreground">데이터를 불러올 수 없습니다</p>
            ) : inventoryStatus ? (
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-sm text-muted-foreground">총 발송 이벤트</p>
                  <p className="text-2xl font-bold">{formatNumber(inventoryStatus.totalEventsSent)}</p>
                </div>
                <div>
                  <p className="text-sm text-muted-foreground">이벤트 간격</p>
                  <p className="text-2xl font-bold">{inventoryStatus.intervalMs}ms</p>
                </div>
                <div>
                  <p className="text-sm text-muted-foreground">카탈로그 상품 수</p>
                  <p className="text-2xl font-bold">{formatNumber(inventoryStatus.catalogSize)}</p>
                </div>
                <div>
                  <p className="text-sm text-muted-foreground">이벤트 비율</p>
                  <p className="text-sm font-medium mt-1">가격 변동 70% / 재입고 30%</p>
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
            <li>트래픽 시뮬레이터: 유저 행동(조회, 클릭, 구매) 이벤트 → Behavior Consumer</li>
            <li>인벤토리 시뮬레이터: 가격 변동/재입고 이벤트 → Notification Service</li>
            <li>두 시뮬레이터는 독립적으로 시작/정지할 수 있습니다</li>
          </ul>
        </CardContent>
      </Card>
    </div>
  )
}
