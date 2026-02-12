import { useMemo, useState } from 'react'
import type { JaegerSpan, JaegerProcess, JaegerTag } from '@/api/types'

// Service color palette
const SERVICE_COLORS: Record<string, string> = {}
const COLOR_PALETTE = [
  '#3b82f6', '#10b981', '#f59e0b', '#8b5cf6', '#ef4444',
  '#06b6d4', '#ec4899', '#84cc16', '#f97316', '#6366f1',
]

function getServiceColor(serviceName: string): string {
  if (!SERVICE_COLORS[serviceName]) {
    const idx = Object.keys(SERVICE_COLORS).length % COLOR_PALETTE.length
    SERVICE_COLORS[serviceName] = COLOR_PALETTE[idx]
  }
  return SERVICE_COLORS[serviceName]
}

interface SpanWaterfallProps {
  spans: JaegerSpan[]
  processes: Record<string, JaegerProcess>
}

interface SpanRow {
  span: JaegerSpan
  depth: number
  serviceName: string
}

function buildSpanTree(spans: JaegerSpan[], processes: Record<string, JaegerProcess>): SpanRow[] {
  const spanMap = new Map(spans.map(s => [s.spanID, s]))
  const children = new Map<string, JaegerSpan[]>()
  let rootSpans: JaegerSpan[] = []

  for (const span of spans) {
    const parentRef = span.references.find(r => r.refType === 'CHILD_OF')
    if (parentRef && spanMap.has(parentRef.spanID)) {
      const list = children.get(parentRef.spanID) || []
      list.push(span)
      children.set(parentRef.spanID, list)
    } else {
      rootSpans.push(span)
    }
  }

  rootSpans.sort((a, b) => a.startTime - b.startTime)

  const result: SpanRow[] = []
  const visit = (span: JaegerSpan, depth: number) => {
    result.push({
      span,
      depth,
      serviceName: processes[span.processID]?.serviceName || 'unknown',
    })
    const kids = children.get(span.spanID) || []
    kids.sort((a, b) => a.startTime - b.startTime)
    for (const child of kids) {
      visit(child, depth + 1)
    }
  }

  for (const root of rootSpans) {
    visit(root, 0)
  }

  return result
}

function getTagValue(tags: JaegerTag[], key: string): string | undefined {
  const tag = tags.find(t => t.key === key)
  return tag?.value?.toString()
}

export function SpanWaterfall({ spans, processes }: SpanWaterfallProps) {
  const [hoveredSpan, setHoveredSpan] = useState<string | null>(null)

  const { rows, traceStart, traceDuration } = useMemo(() => {
    const rows = buildSpanTree(spans, processes)
    const traceStart = Math.min(...spans.map(s => s.startTime))
    const traceEnd = Math.max(...spans.map(s => s.startTime + s.duration))
    return {
      rows,
      traceStart,
      traceDuration: traceEnd - traceStart,
    }
  }, [spans, processes])

  if (rows.length === 0) return <p className="text-muted-foreground text-sm">No spans</p>

  const ROW_HEIGHT = 28
  const LEFT_PANEL_WIDTH = 320
  const SVG_WIDTH = 600
  const TOTAL_WIDTH = LEFT_PANEL_WIDTH + SVG_WIDTH

  return (
    <div className="overflow-x-auto border rounded-lg bg-background">
      {/* Time axis header */}
      <div className="flex border-b" style={{ minWidth: TOTAL_WIDTH }}>
        <div
          className="shrink-0 px-3 py-1 text-xs font-medium text-muted-foreground border-r"
          style={{ width: LEFT_PANEL_WIDTH }}
        >
          Service / Operation
        </div>
        <svg width={SVG_WIDTH} height={20} className="shrink-0">
          {[0, 0.25, 0.5, 0.75, 1].map(pct => (
            <g key={pct}>
              <line
                x1={pct * SVG_WIDTH}
                y1={0}
                x2={pct * SVG_WIDTH}
                y2={20}
                stroke="currentColor"
                strokeOpacity={0.15}
              />
              <text
                x={pct * SVG_WIDTH + 2}
                y={14}
                fontSize={10}
                fill="currentColor"
                opacity={0.5}
              >
                {((traceDuration * pct) / 1000).toFixed(1)}ms
              </text>
            </g>
          ))}
        </svg>
      </div>

      {/* Span rows */}
      <div style={{ minWidth: TOTAL_WIDTH }}>
        {rows.map(({ span, depth, serviceName }) => {
          const hasError =
            span.tags.some(t => t.key === 'error' && t.value === true) ||
            span.tags.some(t => t.key === 'otel.status_code' && t.value === 'ERROR')
          const color = hasError ? '#ef4444' : getServiceColor(serviceName)
          const barStart = ((span.startTime - traceStart) / traceDuration) * SVG_WIDTH
          const barWidth = Math.max((span.duration / traceDuration) * SVG_WIDTH, 2)
          const isHovered = hoveredSpan === span.spanID

          return (
            <div
              key={span.spanID}
              className={`flex items-center border-b transition-colors ${
                isHovered ? 'bg-muted/50' : 'hover:bg-muted/30'
              }`}
              style={{ height: ROW_HEIGHT }}
              onMouseEnter={() => setHoveredSpan(span.spanID)}
              onMouseLeave={() => setHoveredSpan(null)}
            >
              {/* Left panel: service + operation */}
              <div
                className="shrink-0 px-2 flex items-center gap-1 overflow-hidden border-r"
                style={{ width: LEFT_PANEL_WIDTH, paddingLeft: 8 + depth * 16 }}
              >
                <span
                  className="inline-block w-2 h-2 rounded-full shrink-0"
                  style={{ backgroundColor: color }}
                />
                <span className="text-xs font-medium truncate" style={{ color }}>
                  {serviceName}
                </span>
                <span className="text-xs text-muted-foreground truncate">
                  {span.operationName}
                </span>
              </div>

              {/* Right panel: timeline bar */}
              <svg width={SVG_WIDTH} height={ROW_HEIGHT} className="shrink-0">
                {/* Grid lines */}
                {[0.25, 0.5, 0.75].map(pct => (
                  <line
                    key={pct}
                    x1={pct * SVG_WIDTH}
                    y1={0}
                    x2={pct * SVG_WIDTH}
                    y2={ROW_HEIGHT}
                    stroke="currentColor"
                    strokeOpacity={0.05}
                  />
                ))}
                {/* Span bar */}
                <rect
                  x={barStart}
                  y={6}
                  width={barWidth}
                  height={ROW_HEIGHT - 12}
                  rx={3}
                  fill={color}
                  opacity={isHovered ? 1 : 0.8}
                />
                {/* Duration label */}
                {barWidth > 40 && (
                  <text
                    x={barStart + barWidth / 2}
                    y={ROW_HEIGHT / 2 + 4}
                    fontSize={10}
                    fill="white"
                    textAnchor="middle"
                  >
                    {(span.duration / 1000).toFixed(1)}ms
                  </text>
                )}
              </svg>
            </div>
          )
        })}
      </div>

      {/* Hovered span details */}
      {hoveredSpan && (() => {
        const row = rows.find(r => r.span.spanID === hoveredSpan)
        if (!row) return null
        const { span, serviceName } = row
        const httpStatus = getTagValue(span.tags, 'http.status_code')
        const httpMethod = getTagValue(span.tags, 'http.method')
        const httpUrl = getTagValue(span.tags, 'http.url')

        return (
          <div className="p-3 border-t bg-muted/30 text-xs space-y-1">
            <div className="flex gap-4">
              <span><strong>Service:</strong> {serviceName}</span>
              <span><strong>Operation:</strong> {span.operationName}</span>
              <span><strong>Duration:</strong> {(span.duration / 1000).toFixed(2)}ms</span>
              <span><strong>SpanID:</strong> {span.spanID.substring(0, 8)}</span>
            </div>
            {(httpMethod || httpUrl || httpStatus) && (
              <div className="flex gap-4 text-muted-foreground">
                {httpMethod && <span>Method: {httpMethod}</span>}
                {httpUrl && <span>URL: {httpUrl}</span>}
                {httpStatus && <span>Status: {httpStatus}</span>}
              </div>
            )}
          </div>
        )
      })()}
    </div>
  )
}
