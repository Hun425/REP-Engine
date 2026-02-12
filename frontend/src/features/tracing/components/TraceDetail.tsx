import { useQuery, useMutation } from '@tanstack/react-query'
import { Loader2, Bookmark } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { getTraceDetail, addBookmark } from '@/api/tracing'
import { SpanWaterfall } from './SpanWaterfall'

interface TraceDetailProps {
  traceId: string
}

export function TraceDetail({ traceId }: TraceDetailProps) {
  const { data: trace, isLoading } = useQuery({
    queryKey: ['trace-detail', traceId],
    queryFn: () => getTraceDetail(traceId),
    enabled: !!traceId,
  })

  const bookmarkMutation = useMutation({
    mutationFn: () => {
      if (!trace) return Promise.reject()
      const rootSpan = trace.spans.reduce((a, b) => a.startTime < b.startTime ? a : b)
      const traceEnd = Math.max(...trace.spans.map(s => s.startTime + s.duration))
      return addBookmark({
        traceId: trace.traceID,
        serviceName: trace.processes[rootSpan.processID]?.serviceName || 'unknown',
        operationName: rootSpan.operationName,
        durationMs: (traceEnd - rootSpan.startTime) / 1000,
      })
    },
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (!trace) {
    return (
      <div className="text-center py-8 text-muted-foreground text-sm">
        Trace not found
      </div>
    )
  }

  const rootSpan = trace.spans.reduce((a, b) => a.startTime < b.startTime ? a : b)
  const traceEnd = Math.max(...trace.spans.map(s => s.startTime + s.duration))
  const traceDurationMs = (traceEnd - rootSpan.startTime) / 1000
  const services = [...new Set(trace.spans.map(s => trace.processes[s.processID]?.serviceName).filter(Boolean))]
  const hasError = trace.spans.some(s =>
    s.tags.some(t => t.key === 'error' && t.value === true) ||
    s.tags.some(t => t.key === 'otel.status_code' && t.value === 'ERROR')
  )

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <CardTitle className="text-base">
            Trace: {trace.traceID.substring(0, 16)}...
          </CardTitle>
          <Button
            variant="outline"
            size="sm"
            onClick={() => bookmarkMutation.mutate()}
            disabled={bookmarkMutation.isPending}
          >
            <Bookmark className="h-4 w-4 mr-1" />
            {bookmarkMutation.isSuccess ? 'Saved' : 'Bookmark'}
          </Button>
        </div>
        <div className="flex gap-3 text-sm text-muted-foreground">
          <span>Duration: <strong className="text-foreground">{traceDurationMs.toFixed(1)}ms</strong></span>
          <span>Spans: <strong className="text-foreground">{trace.spans.length}</strong></span>
          <span>Services: <strong className="text-foreground">{services.length}</strong></span>
          {hasError && <Badge variant="destructive">Error</Badge>}
        </div>
        <div className="flex gap-1 flex-wrap mt-1">
          {services.map(s => (
            <Badge key={s} variant="secondary" className="text-xs">{s}</Badge>
          ))}
        </div>
      </CardHeader>
      <CardContent>
        <SpanWaterfall spans={trace.spans} processes={trace.processes} />
      </CardContent>
    </Card>
  )
}
