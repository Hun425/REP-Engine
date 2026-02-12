import { NavLink } from 'react-router-dom'
import { LayoutDashboard, Search, Activity, Play, GitBranch, FlaskConical, Waypoints } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useUIStore } from '@/stores/uiStore'

const navItems = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/recommendations', label: '추천 검색', icon: Search },
  { to: '/monitoring', label: '모니터링', icon: Activity },
  { to: '/simulator', label: '시뮬레이터', icon: Play },
  { to: '/pipeline', label: '파이프라인', icon: GitBranch },
  { to: '/load-test', label: '부하 테스트', icon: FlaskConical },
  { to: '/tracing', label: '트레이싱', icon: Waypoints },
]

export function Sidebar() {
  const { sidebarOpen } = useUIStore()

  return (
    <aside
      className={cn(
        'fixed left-0 top-14 z-40 h-[calc(100vh-3.5rem)] w-64 border-r bg-background transition-transform duration-300 lg:translate-x-0',
        sidebarOpen ? 'translate-x-0' : '-translate-x-full'
      )}
    >
      <nav className="flex flex-col gap-2 p-4">
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
                isActive
                  ? 'bg-secondary text-secondary-foreground'
                  : 'text-muted-foreground hover:bg-secondary hover:text-secondary-foreground'
              )
            }
          >
            <item.icon className="h-4 w-4" />
            {item.label}
          </NavLink>
        ))}
      </nav>

      <div className="absolute bottom-4 left-4 right-4">
        <div className="rounded-lg bg-muted p-4">
          <p className="text-xs text-muted-foreground">
            REP-Engine v1.0
          </p>
          <p className="text-xs text-muted-foreground">
            실시간 추천 엔진
          </p>
        </div>
      </div>
    </aside>
  )
}
