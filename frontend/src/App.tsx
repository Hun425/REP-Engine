import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { MainLayout } from '@/components/layout/MainLayout'
import { DashboardPage } from '@/features/dashboard/DashboardPage'
import { RecommendationsPage } from '@/features/recommendations/RecommendationsPage'
import { MonitoringPage } from '@/features/monitoring/MonitoringPage'
import { SimulatorPage } from '@/features/simulator/SimulatorPage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<MainLayout />}>
          <Route index element={<DashboardPage />} />
          <Route path="recommendations" element={<RecommendationsPage />} />
          <Route path="monitoring" element={<MonitoringPage />} />
          <Route path="simulator" element={<SimulatorPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
