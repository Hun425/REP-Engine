# Phase 6: React Frontend (통합 대시보드)

## 1. 개요

REP-Engine 시스템의 **통합 대시보드**를 React로 구현합니다.

### 주요 기능
- **추천 검색**: 유저 ID로 개인화된 추천 상품 조회
- **모니터링**: Grafana 대시보드 임베드
- **시뮬레이터 제어**: 트래픽 시뮬레이터 시작/정지

---

## 2. 기술 스택

| 분류 | 기술 | 버전 | 선택 이유 |
|-----|------|------|----------|
| Framework | React | 18+ | 컴포넌트 기반 UI |
| Language | TypeScript | 5.x | 타입 안정성 |
| Build | Vite | 7.x | 빠른 HMR |
| Server State | TanStack Query | 5.x | 캐싱, 자동 리페치 |
| Client State | Zustand | 4.x | 간단한 API |
| Styling | Tailwind CSS | 4.x | 유틸리티 우선 |
| HTTP | Axios | 1.x | 인터셉터 지원 |
| Charts | Recharts | 2.x | React 친화적 |

---

## 3. 프로젝트 구조

```
frontend/
├── src/
│   ├── api/                    # API 통신
│   │   ├── client.ts           # Axios 인스턴스
│   │   ├── recommendation.ts   # 추천 API
│   │   ├── simulator.ts        # 시뮬레이터 API
│   │   └── types.ts            # 타입 정의
│   ├── components/
│   │   ├── ui/                 # 기본 UI 컴포넌트
│   │   └── layout/             # 레이아웃
│   ├── features/               # 기능별 모듈
│   │   ├── dashboard/
│   │   ├── recommendations/
│   │   ├── monitoring/
│   │   └── simulator/
│   ├── stores/                 # Zustand 스토어
│   └── lib/                    # 유틸리티
├── Dockerfile
├── nginx.conf
└── package.json
```

---

## 4. 페이지 구성

| 경로 | 페이지 | 설명 |
|-----|--------|------|
| `/` | Dashboard | 시스템 요약 + 퀵 액션 |
| `/recommendations` | 추천 검색 | 유저 ID로 추천 조회 |
| `/monitoring` | 모니터링 | Grafana 임베드 |
| `/simulator` | 시뮬레이터 | 시작/정지 제어 |

---

## 5. API 연동

### 5.1 추천 API

```typescript
// GET /api/v1/recommendations/{userId}
interface RecommendationResponse {
  userId: string
  recommendations: ProductRecommendation[]
  strategy: 'knn' | 'popularity' | 'category_best' | 'fallback'
  latencyMs: number
}
```

### 5.2 시뮬레이터 API

```typescript
// GET /api/v1/simulator/status
interface SimulatorStatus {
  isRunning: boolean
  totalEventsSent: number
  userCount: number
  delayMillis: number
}

// POST /api/v1/simulator/start
// POST /api/v1/simulator/stop
```

---

## 6. 백엔드 수정사항

### 6.1 SimulatorController 추가

```kotlin
// simulator/src/main/kotlin/.../controller/SimulatorController.kt
@RestController
@RequestMapping("/api/v1/simulator")
class SimulatorController(
    private val trafficSimulator: TrafficSimulator
) {
    @GetMapping("/status")
    fun getStatus(): ResponseEntity<TrafficSimulator.SimulationStatus>

    @PostMapping("/start")
    fun start(
        @RequestParam userCount: Int,
        @RequestParam delayMillis: Long
    ): ResponseEntity<TrafficSimulator.SimulationStatus>

    @PostMapping("/stop")
    fun stop(): ResponseEntity<TrafficSimulator.SimulationStatus>
}
```

### 6.2 CORS 설정

```kotlin
// simulator, recommendation-api 모듈에 추가
@Configuration
class WebConfig : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:3001")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
    }
}
```

### 6.3 Gradle 의존성

```kotlin
// simulator/build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-web")
```

---

## 7. Docker 설정

### 7.1 docker-compose.yml

```yaml
frontend:
  build:
    context: ../frontend
    dockerfile: Dockerfile
  container_name: rep-frontend
  ports:
    - "3001:80"
  depends_on:
    - grafana
```

### 7.2 Grafana 설정 (iframe 허용)

```yaml
environment:
  - GF_AUTH_ANONYMOUS_ENABLED=true
  - GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer
  - GF_SECURITY_ALLOW_EMBEDDING=true
```

---

## 8. 실행 방법

### 개발 모드

```bash
cd frontend
npm install
npm run dev  # http://localhost:3001
```

### 프로덕션 (Docker)

```bash
cd docker
docker-compose up --build frontend
```

---

## 9. 성공 기준

- [ ] 유저 ID로 추천 검색 가능
- [ ] 인기 상품 조회 가능
- [ ] Grafana 대시보드 표시
- [ ] 시뮬레이터 시작/정지 가능
- [ ] Docker 빌드 성공

---

## 10. 관련 문서

- [마스터 설계서](./마스터 설계서.md)
- [recommendation-api 모듈](./module/05-recommendation-api.md)
- [simulator 모듈](./module/03-simulator.md)
- [Infrastructure](./infrastructure.md)
