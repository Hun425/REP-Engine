import { Menu, Moon, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useUIStore } from '@/stores/uiStore'

export function Header() {
  const { toggleSidebar, theme, toggleTheme } = useUIStore()

  return (
    <header className="sticky top-0 z-50 flex h-14 items-center gap-4 border-b bg-background px-4 lg:px-6">
      <Button variant="ghost" size="icon" onClick={toggleSidebar} className="lg:hidden">
        <Menu className="h-5 w-5" />
        <span className="sr-only">Toggle sidebar</span>
      </Button>

      <div className="flex-1">
        <h1 className="text-lg font-semibold">REP-Engine Dashboard</h1>
      </div>

      <Button variant="ghost" size="icon" onClick={toggleTheme}>
        {theme === 'light' ? (
          <Moon className="h-5 w-5" />
        ) : (
          <Sun className="h-5 w-5" />
        )}
        <span className="sr-only">Toggle theme</span>
      </Button>
    </header>
  )
}
