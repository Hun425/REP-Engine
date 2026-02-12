import { Badge } from '@/components/ui/badge'
import type { TraceSummary } from '@/api/types'

interface TraceListProps {
  traces: TraceSummary[]
  selectedTraceId: string | null
  onSelect: (traceId: string) => void
}

export function TraceList({ traces, selectedTraceId, onSelect }: TraceListProps) {
  if (traces.length === 0) {
    return (
      <div className="text-center py-8 text-muted-foreground text-sm">
        No traces found. Try adjusting your search criteria.
      </div>
    )
  }

  return (
    <div className="border rounded-lg overflow-hidden">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b bg-muted/50">
            <th className="text-left px-3 py-2 font-medium">Trace ID</th>
            <th className="text-left px-3 py-2 font-medium">Service</th>
            <th className="text-left px-3 py-2 font-medium">Operation</th>
            <th className="text-right px-3 py-2 font-medium">Duration</th>
            <th className="text-right px-3 py-2 font-medium">Spans</th>
            <th className="text-right px-3 py-2 font-medium">Services</th>
            <th className="text-left px-3 py-2 font-medium">Time</th>
          </tr>
        </thead>
        <tbody>
          {traces.map(trace => (
            <tr
              key={trace.traceId}
              onClick={() => onSelect(trace.traceId)}
              className={`border-b cursor-pointer transition-colors ${
                selectedTraceId === trace.traceId
                  ? 'bg-primary/10'
                  : 'hover:bg-muted/30'
              }`}
            >
              <td className="px-3 py-2 font-mono text-xs">
                {trace.traceId.substring(0, 12)}...
                {trace.hasError && (
                  <Badge variant="destructive" className="ml-2 text-[10px] px-1 py-0">
                    ERROR
                  </Badge>
                )}
              </td>
              <td className="px-3 py-2">{trace.rootServiceName}</td>
              <td className="px-3 py-2 text-muted-foreground truncate max-w-[200px]">
                {trace.rootOperationName}
              </td>
              <td className="px-3 py-2 text-right font-mono">
                <span className={trace.durationMs > 500 ? 'text-yellow-500' : ''}>
                  {trace.durationMs.toFixed(1)}ms
                </span>
              </td>
              <td className="px-3 py-2 text-right">{trace.spanCount}</td>
              <td className="px-3 py-2 text-right">{trace.serviceCount}</td>
              <td className="px-3 py-2 text-muted-foreground text-xs">
                {new Date(trace.startTime / 1000).toLocaleTimeString()}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
