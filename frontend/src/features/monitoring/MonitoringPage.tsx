import { useState } from 'react'
import { Maximize2, Minimize2, RefreshCw } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { API_URLS } from '@/api/client'

export function MonitoringPage() {
  const [isFullscreen, setIsFullscreen] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)

  const grafanaUrl = `${API_URLS.grafana}/d/rep-engine-overview/overview?orgId=1&kiosk`

  const handleRefresh = () => {
    setRefreshKey((prev) => prev + 1)
  }

  const toggleFullscreen = () => {
    setIsFullscreen(!isFullscreen)
  }

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">모니터링</h2>
          <p className="text-muted-foreground">
            Grafana 대시보드로 시스템 상태를 실시간으로 확인하세요
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={handleRefresh}>
            <RefreshCw className="h-4 w-4 mr-2" />
            새로고침
          </Button>
          <Button variant="outline" size="sm" onClick={toggleFullscreen}>
            {isFullscreen ? (
              <Minimize2 className="h-4 w-4 mr-2" />
            ) : (
              <Maximize2 className="h-4 w-4 mr-2" />
            )}
            {isFullscreen ? '축소' : '전체화면'}
          </Button>
        </div>
      </div>

      {/* Grafana 임베드 */}
      <Card className={isFullscreen ? 'fixed inset-4 z-50' : ''}>
        <CardHeader className={isFullscreen ? 'pb-2' : ''}>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle>REP-Engine Dashboard</CardTitle>
              <CardDescription>실시간 메트릭 및 시스템 상태</CardDescription>
            </div>
            {isFullscreen && (
              <Button variant="ghost" size="sm" onClick={toggleFullscreen}>
                <Minimize2 className="h-4 w-4" />
              </Button>
            )}
          </div>
        </CardHeader>
        <CardContent className={isFullscreen ? 'h-[calc(100%-5rem)]' : ''}>
          <div
            className={`rounded-lg border bg-muted ${
              isFullscreen ? 'h-full' : 'h-[600px]'
            }`}
          >
            <iframe
              key={refreshKey}
              src={grafanaUrl}
              className="w-full h-full rounded-lg"
              title="Grafana Dashboard"
            />
          </div>
          <p className="text-xs text-muted-foreground mt-2">
            💡 Grafana가 표시되지 않으면 <code>docker-compose up</code>으로 Grafana를 시작하고,{' '}
            <a
              href={API_URLS.grafana}
              target="_blank"
              rel="noopener noreferrer"
              className="text-primary underline"
            >
              {API_URLS.grafana}
            </a>
            에서 직접 접속해보세요.
          </p>
        </CardContent>
      </Card>
    </div>
  )
}
