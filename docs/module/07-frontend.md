# frontend 모듈 설명서

## 이 모듈이 하는 일 (한 줄 요약)

**REP-Engine 시스템의 통합 대시보드 - 추천 검색, 모니터링, 시뮬레이터 제어 UI**

---

## 비유로 이해하기

자동차 계기판을 생각해보세요. 속도계, 연료계, 엔진 상태 등 차량의 모든 정보를 한눈에 볼 수 있죠.

이 프론트엔드는 **REP-Engine의 계기판** 역할을 합니다:
- 추천 검색: 네비게이션처럼 유저에게 맞는 상품을 안내
- 모니터링: 계기판처럼 시스템 상태를 실시간 표시
- 시뮬레이터: 액셀러레이터처럼 트래픽 생성을 제어

---

## 파일 구조

```
frontend/
├── src/
│   ├── main.tsx                    # 앱 진입점
│   ├── App.tsx                     # 라우터 설정
│   ├── index.css                   # Tailwind 테마
│   │
│   ├── api/                        # API 통신
│   │   ├── client.ts               # Axios 인스턴스
│   │   ├── recommendation.ts       # 추천 API
│   │   ├── simulator.ts            # 시뮬레이터 API
│   │   └── types.ts                # 타입 정의
│   │
│   ├── components/
│   │   ├── ui/                     # 기본 UI 컴포넌트
│   │   │   ├── button.tsx
│   │   │   ├── card.tsx
│   │   │   ├── input.tsx
│   │   │   ├── badge.tsx
│   │   │   └── skeleton.tsx
│   │   └── layout/                 # 레이아웃
│   │       ├── Header.tsx
│   │       ├── Sidebar.tsx
│   │       └── MainLayout.tsx
│   │
│   ├── features/                   # 기능별 모듈
│   │   ├── dashboard/
│   │   │   └── DashboardPage.tsx
│   │   ├── recommendations/
│   │   │   └── RecommendationsPage.tsx
│   │   ├── monitoring/
│   │   │   └── MonitoringPage.tsx
│   │   └── simulator/
│   │       └── SimulatorPage.tsx
│   │
│   ├── stores/                     # Zustand 스토어
│   │   └── uiStore.ts
│   │
│   └── lib/                        # 유틸리티
│       └── utils.ts
│
├── Dockerfile                      # Docker 빌드
├── nginx.conf                      # Nginx 설정
├── .env.example                    # 환경변수 예시
├── vite.config.ts                  # Vite 설정
├── tsconfig.json                   # TypeScript 설정
└── package.json                    # 의존성
```

---

## 핵심 파일 설명

### 1. main.tsx - 앱 진입점

```tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60, // 1분간 캐시
      retry: 2,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={queryClient}>
    <App />
  </QueryClientProvider>
)
```

**역할**: TanStack Query Provider로 앱을 감싸서 전역 캐싱 활성화

---

### 2. api/types.ts - API 타입 정의

```typescript
interface RecommendationResponse {
  userId: string
  recommendations: ProductRecommendation[]
  strategy: 'knn' | 'popularity' | 'category_best' | 'fallback'
  latencyMs: number
}

interface ProductRecommendation {
  productId: string
  productName: string
  category: string
  price: number
  score: number
}

interface SimulatorStatus {
  isRunning: boolean
  totalEventsSent: number
  userCount: number
  delayMillis: number
}
```

---

### 3. features/recommendations/RecommendationsPage.tsx

```tsx
export function RecommendationsPage() {
  const [userId, setUserId] = useState('')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['recommendations', userId],
    queryFn: () => getRecommendations({ userId }),
    enabled: !!userId,
  })

  return (
    // 검색 폼 + 결과 그리드
  )
}
```

**역할**: 유저 ID를 입력받아 추천 상품을 조회하고 표시

---

### 4. stores/uiStore.ts - UI 상태 관리

```typescript
export const useUIStore = create<UIState>()(
  persist(
    (set) => ({
      sidebarOpen: true,
      theme: 'light',
      toggleSidebar: () => set((state) => ({ sidebarOpen: !state.sidebarOpen })),
      toggleTheme: () => set((state) => ({
        theme: state.theme === 'light' ? 'dark' : 'light'
      })),
    }),
    { name: 'rep-ui-storage' }
  )
)
```

**역할**: 사이드바 상태, 테마 등 UI 상태를 localStorage에 저장

---

## 데이터 흐름도

```
┌─────────────────────────────────────────────────────────────────┐
│                         Frontend                                │
│                                                                 │
│   ┌──────────┐     ┌───────────────┐     ┌─────────────┐       │
│   │   Page   │────▶│ TanStack Query│────▶│ API Client  │───────┼──▶ Backend
│   │Component │◀────│   (Cache)     │◀────│   (Axios)   │◀──────┼──
│   └──────────┘     └───────────────┘     └─────────────┘       │
│         │                                                       │
│         │          ┌───────────────┐                           │
│         └─────────▶│  Zustand      │ (UI State)                │
│                    │  Store        │                           │
│                    └───────────────┘                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## 주요 기술 용어 정리

| 용어 | 설명 |
|-----|------|
| **TanStack Query** | 서버 상태 관리 라이브러리. API 호출 결과를 캐싱하고 자동으로 리페치 |
| **Zustand** | 클라이언트 상태 관리 라이브러리. Redux보다 간단한 API |
| **Vite** | 빌드 도구. Webpack보다 빠른 HMR(Hot Module Replacement) |
| **Tailwind CSS** | 유틸리티 우선 CSS 프레임워크. `className="bg-blue-500"` 형태로 스타일링 |
| **shadcn/ui** | Radix UI 기반 컴포넌트 라이브러리. 복사해서 사용하는 방식 |

---

## 다른 모듈과의 연결

```
┌─────────────┐
│  frontend   │
└──────┬──────┘
       │
       ▼
┌──────────────────────────────────────────────────────┐
│                    REST API                           │
├─────────────────────┬────────────────────────────────┤
│                     │                                │
▼                     ▼                                ▼
┌─────────────┐   ┌─────────────┐   ┌─────────────────┐
│ recommendation│  │  simulator  │   │    grafana      │
│    -api       │  │             │   │   (iframe)      │
│   :8082       │  │    :8080    │   │    :3000        │
└─────────────┘   └─────────────┘   └─────────────────┘
```

---

## 개발 및 실행

### 개발 모드

```bash
cd frontend
npm install
npm run dev
# http://localhost:3001
```

### 프로덕션 빌드

```bash
npm run build
# dist/ 폴더에 빌드 결과물 생성
```

### Docker 실행

```bash
cd docker
docker-compose up frontend
# http://localhost:3001
```

---

## 환경 변수

### VITE_ 접두어 규칙

Vite는 `VITE_` 접두어가 붙은 환경변수만 클라이언트 코드에서 접근할 수 있습니다.
빌드 시점에 `import.meta.env.VITE_*`로 치환됩니다.

```typescript
// 사용 예시
const apiUrl = import.meta.env.VITE_RECOMMENDATION_API_URL
```

### 환경변수 목록

`.env.example` 파일을 `.env`로 복사 후 값을 수정하세요.

| 변수 | 예시 값 | 설명 |
|-----|--------|------|
| `VITE_RECOMMENDATION_API_URL` | `http://localhost:8082` | 추천 API URL |
| `VITE_SIMULATOR_API_URL` | `http://localhost:8080` | 시뮬레이터 API URL |
| `VITE_GRAFANA_URL` | `http://localhost:3000` | Grafana 대시보드 URL |
| `VITE_ENABLE_MONITORING` | `true` | 모니터링 탭 활성화 여부 |
| `VITE_ENABLE_SIMULATOR` | `true` | 시뮬레이터 탭 활성화 여부 |

### .env.example

```bash
# API URLs
VITE_RECOMMENDATION_API_URL=http://localhost:8082
VITE_SIMULATOR_API_URL=http://localhost:8080
VITE_GRAFANA_URL=http://localhost:3000

# Feature Flags (optional)
VITE_ENABLE_MONITORING=true
VITE_ENABLE_SIMULATOR=true
```

### 환경별 설정

| 환경 | 설정 방법 |
|------|----------|
| 로컬 개발 | `.env` 파일 생성 |
| Docker | `docker-compose.yml`의 `environment` 섹션 |
| 프로덕션 | 빌드 시점에 환경변수 주입 |

---

## 포트 매핑

| 서비스 | 개발 모드 | Docker |
|--------|----------|--------|
| Frontend | 3001 | 3001 (80 내부) |
| Recommendation API | 8082 | 8082 |
| Simulator | 8080 | 8080 |
| Grafana | 3000 | 3000 |
