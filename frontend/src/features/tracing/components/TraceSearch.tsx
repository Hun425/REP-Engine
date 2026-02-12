import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { getServices } from '@/api/tracing'

interface TraceSearchProps {
  onSearch: (params: {
    service?: string
    limit: number
    minDuration?: string
    maxDuration?: string
    start?: number
    end?: number
  }) => void
}

export function TraceSearch({ onSearch }: TraceSearchProps) {
  const [service, setService] = useState<string>('')
  const [limit, setLimit] = useState(20)
  const [minDuration, setMinDuration] = useState('')
  const [maxDuration, setMaxDuration] = useState('')
  const [timeRange, setTimeRange] = useState('1h')

  const { data: services = [] } = useQuery({
    queryKey: ['tracing-services'],
    queryFn: getServices,
    staleTime: 30000,
  })

  const handleSearch = () => {
    const now = Date.now() * 1000 // Jaeger uses microseconds
    const rangeMs: Record<string, number> = {
      '15m': 15 * 60 * 1000,
      '1h': 60 * 60 * 1000,
      '6h': 6 * 60 * 60 * 1000,
      '24h': 24 * 60 * 60 * 1000,
    }
    const range = rangeMs[timeRange] || rangeMs['1h']

    onSearch({
      service: service || undefined,
      limit,
      minDuration: minDuration || undefined,
      maxDuration: maxDuration || undefined,
      start: (now - range * 1000),
      end: now,
    })
  }

  return (
    <div className="flex flex-wrap gap-3 items-end">
      <div className="space-y-1">
        <label className="text-xs text-muted-foreground">Service</label>
        <select
          value={service}
          onChange={e => setService(e.target.value)}
          className="h-9 px-3 rounded-md border bg-background text-sm"
        >
          <option value="">All Services</option>
          {services.map(s => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
      </div>

      <div className="space-y-1">
        <label className="text-xs text-muted-foreground">Time Range</label>
        <select
          value={timeRange}
          onChange={e => setTimeRange(e.target.value)}
          className="h-9 px-3 rounded-md border bg-background text-sm"
        >
          <option value="15m">15 min</option>
          <option value="1h">1 hour</option>
          <option value="6h">6 hours</option>
          <option value="24h">24 hours</option>
        </select>
      </div>

      <div className="space-y-1">
        <label className="text-xs text-muted-foreground">Min Duration</label>
        <Input
          value={minDuration}
          onChange={e => setMinDuration(e.target.value)}
          placeholder="e.g. 100ms"
          className="h-9 w-28"
        />
      </div>

      <div className="space-y-1">
        <label className="text-xs text-muted-foreground">Max Duration</label>
        <Input
          value={maxDuration}
          onChange={e => setMaxDuration(e.target.value)}
          placeholder="e.g. 5s"
          className="h-9 w-28"
        />
      </div>

      <div className="space-y-1">
        <label className="text-xs text-muted-foreground">Limit</label>
        <Input
          type="number"
          value={limit}
          onChange={e => setLimit(Number(e.target.value))}
          className="h-9 w-20"
          min={1}
          max={200}
        />
      </div>

      <Button onClick={handleSearch} size="sm" className="h-9">
        <Search className="h-4 w-4 mr-1" />
        Search
      </Button>
    </div>
  )
}
