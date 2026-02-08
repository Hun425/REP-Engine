import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Search, Activity, Play, ArrowRight, TrendingUp, Zap } from 'lucide-react'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { getSimulatorStatus } from '@/api/simulator'
import { formatNumber } from '@/lib/utils'

// 이벤트 히스토리 타입
interface EventHistoryPoint {
  time: string
  events: number
}

export function DashboardPage() {
  // 시뮬레이터 상태 조회
  const statusQuery = useQuery({
    queryKey: ['simulator', 'status'],
    queryFn: getSimulatorStatus,
    refetchInterval: 3000, // 3초마다 갱신
    retry: false,
  })

  // 이벤트 히스토리 (클라이언트 사이드 추적)
  const [eventHistory, setEventHistory] = useState<EventHistoryPoint[]>([])
  const [lastEventCount, setLastEventCount] = useState<number>(0)

  // 이벤트 히스토리 업데이트
  useEffect(() => {
    if (statusQuery.data) {
      const currentCount = statusQuery.data.totalEventsSent
      const now = new Date().toLocaleTimeString('ko-KR', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      })

      // 초당 이벤트 계산 (3초 간격이므로 / 3)
      const eventsPerSecond = lastEventCount > 0
        ? Math.round((currentCount - lastEventCount) / 3)
        : 0

      setEventHistory(prev => {
        const newHistory = [...prev, { time: now, events: eventsPerSecond }]
        // 최근 20개 포인트만 유지
        return newHistory.slice(-20)
      })
      setLastEventCount(currentCount)
    }
  }, [statusQuery.data?.totalEventsSent])

  const status = statusQuery.data
  const isRunning = status?.isRunning ?? false
  const isConnected = !statusQuery.isError

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <div>
        <h2 className="text-2xl font-bold tracking-tight">Dashboard</h2>
        <p className="text-muted-foreground">
          REP-Engine 시스템 현황을 한눈에 확인하세요
        </p>
      </div>

      {/* 상태 카드 */}
      <div className="grid gap-4 md:grid-cols-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">시스템 상태</CardTitle>
            <Activity className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {statusQuery.isLoading ? (
              <Skeleton className="h-6 w-20" />
            ) : isConnected ? (
              <>
                <Badge variant="success">HEALTHY</Badge>
                <p className="text-xs text-muted-foreground mt-2">
                  API 연결 정상
                </p>
              </>
            ) : (
              <>
                <Badge variant="destructive">OFFLINE</Badge>
                <p className="text-xs text-muted-foreground mt-2">
                  백엔드 연결 실패
                </p>
              </>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">시뮬레이터</CardTitle>
            <Play className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {statusQuery.isLoading ? (
              <Skeleton className="h-8 w-16" />
            ) : isConnected ? (
              <>
                <Badge variant={isRunning ? 'success' : 'secondary'}>
                  {isRunning ? '● 실행 중' : '○ 정지됨'}
                </Badge>
                <p className="text-xs text-muted-foreground mt-2">
                  {isRunning ? `${status?.userCount}명 유저 활성` : '대기 중'}
                </p>
              </>
            ) : (
              <>
                <Badge variant="outline">-</Badge>
                <p className="text-xs text-muted-foreground mt-2">
                  연결 필요
                </p>
              </>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">총 이벤트</CardTitle>
            <TrendingUp className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {statusQuery.isLoading ? (
              <Skeleton className="h-8 w-24" />
            ) : isConnected && status ? (
              <>
                <div className="text-2xl font-bold">
                  {formatNumber(status.totalEventsSent)}
                </div>
                <p className="text-xs text-muted-foreground">
                  Kafka로 발송된 이벤트
                </p>
              </>
            ) : (
              <>
                <div className="text-2xl font-bold">-</div>
                <p className="text-xs text-muted-foreground">
                  데이터 없음
                </p>
              </>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">처리량</CardTitle>
            <Zap className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {statusQuery.isLoading ? (
              <Skeleton className="h-8 w-20" />
            ) : isConnected && status && isRunning ? (
              <>
                <div className="text-2xl font-bold">
                  ~{Math.round(status.userCount / (status.delayMillis / 1000))}/s
                </div>
                <p className="text-xs text-muted-foreground">
                  예상 초당 이벤트
                </p>
              </>
            ) : (
              <>
                <div className="text-2xl font-bold">0/s</div>
                <p className="text-xs text-muted-foreground">
                  {isRunning ? '계산 중...' : '시뮬레이터 정지됨'}
                </p>
              </>
            )}
          </CardContent>
        </Card>
      </div>

      {/* 이벤트 트렌드 차트 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <TrendingUp className="h-5 w-5" />
            실시간 이벤트 트렌드
          </CardTitle>
          <CardDescription>
            초당 처리되는 이벤트 수 (최근 60초)
          </CardDescription>
        </CardHeader>
        <CardContent>
          {eventHistory.length > 1 ? (
            <ResponsiveContainer width="100%" height={250}>
              <LineChart data={eventHistory}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                <XAxis
                  dataKey="time"
                  tick={{ fontSize: 12 }}
                  className="text-muted-foreground"
                />
                <YAxis
                  tick={{ fontSize: 12 }}
                  className="text-muted-foreground"
                />
                <Tooltip
                  contentStyle={{
                    backgroundColor: 'var(--color-card)',
                    border: '1px solid var(--color-border)',
                    borderRadius: '6px'
                  }}
                  labelStyle={{ color: 'var(--color-foreground)' }}
                />
                <Line
                  type="monotone"
                  dataKey="events"
                  stroke="var(--color-primary)"
                  strokeWidth={2}
                  dot={false}
                  name="이벤트/초"
                />
              </LineChart>
            </ResponsiveContainer>
          ) : (
            <div className="h-[250px] flex items-center justify-center text-muted-foreground">
              {isConnected ? (
                <p>시뮬레이터를 시작하면 실시간 데이터가 표시됩니다</p>
              ) : (
                <p>백엔드 연결 후 데이터가 표시됩니다</p>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* 퀵 액션 */}
      <Card>
        <CardHeader>
          <CardTitle>Quick Actions</CardTitle>
          <CardDescription>자주 사용하는 기능에 빠르게 접근하세요</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          <Link to="/recommendations">
            <Button variant="outline" className="w-full justify-between">
              <span className="flex items-center gap-2">
                <Search className="h-4 w-4" />
                추천 검색하기
              </span>
              <ArrowRight className="h-4 w-4" />
            </Button>
          </Link>

          <Link to="/simulator">
            <Button variant="outline" className="w-full justify-between">
              <span className="flex items-center gap-2">
                <Play className="h-4 w-4" />
                시뮬레이터 제어
              </span>
              <ArrowRight className="h-4 w-4" />
            </Button>
          </Link>

          <Link to="/monitoring">
            <Button variant="outline" className="w-full justify-between">
              <span className="flex items-center gap-2">
                <Activity className="h-4 w-4" />
                모니터링 보기
              </span>
              <ArrowRight className="h-4 w-4" />
            </Button>
          </Link>
        </CardContent>
      </Card>

      {/* 가이드 */}
      <Card>
        <CardHeader>
          <CardTitle>시작 가이드</CardTitle>
          <CardDescription>REP-Engine을 처음 사용하시나요?</CardDescription>
        </CardHeader>
        <CardContent>
          <ol className="list-decimal list-inside space-y-2 text-sm text-muted-foreground">
            <li>
              <strong>시뮬레이터 시작:</strong> 시뮬레이터 페이지에서 트래픽 생성을 시작하세요
            </li>
            <li>
              <strong>데이터 확인:</strong> 모니터링 페이지에서 Kafka 이벤트가 처리되는지 확인하세요
            </li>
            <li>
              <strong>추천 테스트:</strong> 추천 검색 페이지에서 유저 ID로 추천 결과를 확인하세요
            </li>
          </ol>
        </CardContent>
      </Card>
    </div>
  )
}
