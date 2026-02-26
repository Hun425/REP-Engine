# PR/커밋 초안: 코드 스타일 통일 + 가독성 리팩토링

## 1) 커밋 내역 추천

아래 순서로 나누면 리뷰가 가장 쉽습니다.

1. `docs: Kotlin+Spring 코드 스타일 가이드 v1 수립 및 한글 문서 추가`
   - `docs/code-style-guide.md`
   - `docs/code-style-guide.ko.md`
   - `docs/readability-refactoring-case-study.ko.md`

2. `chore: 루트 포맷/린트 기준 정비 (.editorconfig, ktlint/detekt 기반)`
   - `.editorconfig`
   - `build.gradle.kts` (ktlint 플러그인/설정 반영분)
   - 모듈별 `build.gradle.kts` 변경분(있으면 포함)

3. `style: Kotlin import/KDoc/Qualifier/로거 스타일 일관화`
   - 전 모듈 Kotlin 스타일 일관화 파일들
   - 와일드카드 import 제거, `@see` 표기 통일, `@param:Qualifier` 통일 등

4. `fix: 설정 하드코딩 제거 및 입력 검증 강화`
   - ES index/topic/table 등 설정 외부화 관련 파일
   - timezone/property 외부화
   - simulator 입력 검증 강화(`@Validated`, 제약 애너테이션, require)

5. `refactor: EventDetector/LoadTestService 가독성 중심 리팩토링`
   - `notification-service/.../EventDetector.kt`
   - `simulator/.../LoadTestService.kt`

6. `chore(frontend): Node 버전 명시 및 ESLint 규칙 보강`
   - `frontend/package.json`
   - `frontend/.nvmrc`
   - `frontend/eslint.config.js`
   - `frontend/src/api/client.ts`
   - `frontend/README.md`

---

## 2) PR 본문 초안 (복붙용)

### 제목
`fix: 코드 스타일 기준 정립 및 가독성 리팩토링 적용`

### 개요
Kotlin + Spring 서버 코드의 스타일 기준을 문서/도구로 고정하고,  
핵심 서비스 코드에 가독성 중심 리팩토링을 적용했습니다.

이번 변경의 목표는 다음 3가지입니다.
1. 팀 공통 스타일 기준 확정(문서 + lint)
2. 하드코딩/환경 의존 요소 설정화
3. 핵심 로직의 "상위 의도 / 하위 구현" 구조 강화

### 주요 변경사항
1. 코드 스타일 가이드 확정
   - `docs/code-style-guide.md` (영문)
   - `docs/code-style-guide.ko.md` (국문)
   - `docs/readability-refactoring-case-study.ko.md` (학습용 before/after)

2. 스타일/포맷 자동화 강화
   - `.editorconfig` 정비
   - ktlint 최신 호환 버전 반영 및 규칙 조정
   - import/KDoc/Qualifier/로깅 스타일 일관화

3. 설정 외부화 및 안정성 보완
   - ES index 이름 하드코딩 제거(설정 주입)
   - scheduler timezone 설정화
   - simulator 입력 검증 강화

4. 가독성 리팩토링
   - `EventDetector`: 조건 해석/배치 발송/알림 생성/관측 로직 분리
   - `LoadTestService`: 시작/중지/수집/시나리오 오케스트레이션 단계 분리

5. 프론트 환경/린트 정리
   - Node 엔진 버전 명시(`.nvmrc`, `package.json`)
   - ESLint 규칙 강화 및 `console.log` 제거

### 테스트
실행한 검증:
1. `./gradlew ktlintFormat -q`
2. `./gradlew ktlintCheck -q`
3. `./gradlew test -q`
4. `./gradlew :notification-service:compileKotlin :simulator:compileKotlin -q`

참고:
1. 일부 조합 실행 시 `common-avro`의 Gradle task implicit dependency 경고/실패가 재현될 수 있음
2. 프론트 빌드는 로컬 Node 버전에 따라 실패 가능(프로젝트는 Node 20.19+ / 22 권장으로 명시)

### 영향 범위
1. backend: `behavior-consumer`, `recommendation-api`, `notification-service`, `simulator`
2. docs: 코드 스타일/리팩토링 가이드 문서 추가
3. frontend: lint/runtime 요구사항 명시

### 리뷰 포인트
1. ktlint 규칙 비활성화 항목이 팀 기준(가독성 우선)에 맞는지
2. EventDetector/LoadTestService의 분해 수준이 과소/과다 추상화가 아닌지
3. 설정 외부화 키 네이밍의 장기 일관성

### 체크리스트
- [ ] 문서 기준과 코드 적용 방향이 일치함
- [ ] 스타일 변경과 동작 변경이 논리적으로 분리됨
- [ ] 주요 모듈 컴파일/테스트 검증 완료
- [ ] 운영 영향(설정 키 추가/변경) 확인 완료

---

## 3) 브랜치명 추천

`codex/style-readability-v1`
