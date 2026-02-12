import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { MainLayout } from '@/components/layout/MainLayout'
import { DashboardPage } from '@/features/dashboard/DashboardPage'
import { RecommendationsPage } from '@/features/recommendations/RecommendationsPage'
import { MonitoringPage } from '@/features/monitoring/MonitoringPage'
import { SimulatorPage } from '@/features/simulator/SimulatorPage'
import { PipelinePage } from '@/features/pipeline/PipelinePage'
import { LoadTestPage } from '@/features/loadtest/LoadTestPage'
import { TracingPage } from '@/features/tracing/TracingPage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<MainLayout />}>
          <Route index element={<DashboardPage />} />
          <Route path="recommendations" element={<RecommendationsPage />} />
          <Route path="monitoring" element={<MonitoringPage />} />
          <Route path="simulator" element={<SimulatorPage />} />
          <Route path="pipeline" element={<PipelinePage />} />
          <Route path="load-test" element={<LoadTestPage />} />
          <Route path="tracing" element={<TracingPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
