import { Outlet } from 'react-router-dom'
import { Header } from './Header'
import { Sidebar } from './Sidebar'
import { useUIStore } from '@/stores/uiStore'
import { cn } from '@/lib/utils'

export function MainLayout() {
  const { sidebarOpen, theme } = useUIStore()

  return (
    <div className={cn('min-h-screen bg-background', theme === 'dark' && 'dark')}>
      <Header />
      <Sidebar />
      <main
        className={cn(
          'min-h-[calc(100vh-3.5rem)] transition-all duration-300',
          sidebarOpen ? 'lg:ml-64' : ''
        )}
      >
        <div className="container mx-auto p-6">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
