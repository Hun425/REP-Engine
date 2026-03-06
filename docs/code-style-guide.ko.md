# REP-Engine Kotlin + Spring 코드 스타일 가이드 (v1)

## 범위

이 문서는 이 저장소의 Kotlin + Spring **서버 사이드** 모듈에 대한 코딩 스타일을 정의합니다.

기본 기준:
1. Kotlin Coding Conventions
2. 이 저장소에 설정된 ktlint 규칙
3. 이 문서(팀 가독성 중심 규칙)

## 핵심 원칙

1. 가독성 우선
2. 축약보다 명시성
3. 모듈 간 예측 가능한 구조
4. 가능한 규칙은 도구로 강제

## Kotlin 기본 규칙

1. 와일드카드 import(`*`) 사용 금지
2. import는 명시적으로 작성하고 정렬 유지
3. 줄 길이는 읽기 좋은 수준 유지(권장 120자)
4. 축약어보다 설명적인 이름 선호
5. KDoc 링크는 `@see docs/phase N.md` 형식 사용

## Spring 구조 규칙

1. 기본은 생성자 주입 사용
2. 생성자 파라미터에 qualifier가 필요하면 `@param:Qualifier("...")` 사용
3. 빈 조립은 `config` 패키지, 비즈니스 로직은 `service` 패키지에 배치
4. Controller에는 비즈니스 로직/영속성 로직을 넣지 않음
5. Repository 접근은 controller가 아닌 service/repository 레이어에서 처리

## Controller 규칙

1. HTTP 관심사(요청/응답, 상태코드, 검증 오류)만 처리
2. 성공 경로는 단순하고 빠르게 읽히도록 유지
3. 잘못된 요청/미존재 분기는 early return 사용
4. 경계에서 DTO/파라미터 검증 수행(`@Valid`, 제약 애너테이션)

## Service 규칙

1. 서비스 메서드는 하나의 비즈니스 의도를 표현
2. 긴 메서드는 기술 단위가 아닌 비즈니스 단계 기준으로 분리
3. 트랜잭션 경계는 서비스 레이어에 둠
4. 숨은 부작용을 피하고 외부 호출 흐름을 코드에 명시

## 예외 처리와 로깅

1. API 경계에서 일관된 오류 응답 정책 사용
2. 예외를 조용히 무시하지 않음
3. 로거 스타일 통일: top-level logger 선언(`private val log = KotlinLogging.logger {}`)
4. 로그 메시지에는 비즈니스 컨텍스트 키 포함(예: userId, productId, requestId)
5. 오류 로그에는 예외 객체 포함(`log.error(e) { "..." }`)

## 영속성과 외부 I/O

1. 인덱스/토픽/테이블 이름을 비즈니스 코드에 하드코딩하지 않음
2. 외부 리소스 이름은 설정(`application.yml`/properties)에서 주입
3. 매핑 로직(entity/document <-> domain model)은 명시적이고 테스트 가능하게 유지

## 테스트 규칙

1. 단위 테스트는 비즈니스 판단/분기 로직 검증
2. 통합 테스트는 Spring wiring + 저장소/메시징 경계 검증
3. 테스트 이름은 구현이 아니라 동작을 설명
4. 프로덕션 영향 버그 수정 시 회귀 테스트 추가

## 도구와 강제 방식

1. `.editorconfig`를 기본 포맷 계약으로 사용
2. 포맷팅: `./gradlew ktlintFormat -q`
3. 스타일 검증: `./gradlew ktlintCheck -q`
4. 동작 회귀 확인: `./gradlew test -q`
5. detekt는 다음 단계(복잡도/설계 스멜 검증)로 도입 권장
