import { useState, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { TraceSearch } from './components/TraceSearch'
import { TraceList } from './components/TraceList'
import { TraceDetail } from './components/TraceDetail'
import { AnomalyList } from './components/AnomalyList'
import { BookmarkList } from './components/BookmarkList'
import { searchTraces } from '@/api/tracing'

type Tab = 'traces' | 'anomalies' | 'bookmarks'

export function TracingPage() {
  const [activeTab, setActiveTab] = useState<Tab>('traces')
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null)
  const [searchParams, setSearchParams] = useState<{
    service?: string
    limit: number
    minDuration?: string
    maxDuration?: string
    start?: number
    end?: number
  }>({ limit: 20 })

  const { data: traces = [], isLoading: tracesLoading } = useQuery({
    queryKey: ['traces', searchParams],
    queryFn: () => searchTraces(searchParams),
    enabled: activeTab === 'traces',
  })

  const handleSearch = useCallback((params: typeof searchParams) => {
    setSearchParams(params)
    setSelectedTraceId(null)
  }, [])

  const handleSelectTrace = useCallback((traceId: string) => {
    setSelectedTraceId(traceId)
    setActiveTab('traces')
  }, [])

  const tabs: { id: Tab; label: string }[] = [
    { id: 'traces', label: 'Trace Explorer' },
    { id: 'anomalies', label: 'Anomalies' },
    { id: 'bookmarks', label: 'Bookmarks' },
  ]

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold">Distributed Tracing</h1>
        <p className="text-sm text-muted-foreground">
          Trace requests across services, detect anomalies, and bookmark interesting traces
        </p>
      </div>

      {/* Tab navigation */}
      <div className="flex gap-1 border-b">
        {tabs.map(tab => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
              activeTab === tab.id
                ? 'border-primary text-primary'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      {activeTab === 'traces' && (
        <div className="space-y-4">
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Search Traces</CardTitle>
            </CardHeader>
            <CardContent>
              <TraceSearch onSearch={handleSearch} />
            </CardContent>
          </Card>

          <TraceList
            traces={traces}
            selectedTraceId={selectedTraceId}
            onSelect={setSelectedTraceId}
          />

          {selectedTraceId && (
            <TraceDetail traceId={selectedTraceId} />
          )}
        </div>
      )}

      {activeTab === 'anomalies' && (
        <AnomalyList onSelectTrace={handleSelectTrace} />
      )}

      {activeTab === 'bookmarks' && (
        <BookmarkList onSelectTrace={handleSelectTrace} />
      )}
    </div>
  )
}
