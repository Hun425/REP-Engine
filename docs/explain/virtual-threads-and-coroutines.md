# Virtual Threads와 Coroutines: 이 프로젝트에서의 동작 원리

## 1. 배경: 왜 "비동기"가 필요한가?

### 전통적인 문제: 스레드 블로킹

서버 애플리케이션은 주로 **I/O 작업**(DB 조회, HTTP 호출, Redis 접근 등)을 합니다.

```
[요청 들어옴] → [ES에 쿼리 보냄] → [응답 대기 50ms...] → [결과 처리] → [응답]
```

이 50ms 동안 해당 스레드는 **아무것도 안 하고 기다립니다(블로킹)**.
전통적인 스레드는 OS 자원을 많이 쓰기 때문에 보통 200~500개 정도만 만들 수 있고,
모든 스레드가 I/O 대기 중이면 새 요청을 처리할 수 없습니다.

이 문제를 해결하는 두 가지 접근법이 있습니다:

---

## 2. 해결법 A: Kotlin Coroutines (코루틴)

### 핵심 개념: "기다리는 동안 스레드를 양보"

코루틴은 **suspend(일시 중단)** 메커니즘으로 이 문제를 해결합니다.

```kotlin
// suspend 함수: "나 여기서 잠깐 멈출 수 있어"라는 표시
suspend fun getUserFromRedis(userId: String): User {
    // awaitSingleOrNull()이 suspend 포인트
    // → 여기서 스레드를 양보하고, Redis 응답 오면 다시 이어서 실행
    return redisTemplate.opsForValue().get(key).awaitSingleOrNull()
}
```

**동작 흐름:**
```
스레드 A: [요청1 처리] → [Redis 호출 - suspend!] → [요청2 처리 시작] → ...
                                                    ↓
                              [Redis 응답 도착] → [요청1 이어서 처리]
```

하나의 스레드가 여러 요청을 **번갈아가며** 처리합니다.
I/O 대기 중에 스레드를 놀리지 않으니 적은 스레드로 많은 요청을 처리할 수 있습니다.

### suspend의 규칙

```kotlin
suspend fun a() {          // suspend 함수
    val x = b()            // b()가 suspend면 → OK, 여기서 양보 가능
    val y = normalFun()    // 일반 함수면 → 양보 없이 블로킹 실행
}

fun normalFun() {          // 일반 함수
    // suspend 함수를 직접 호출할 수 없음!
    // val x = a()  ← 컴파일 에러
}
```

**핵심:** `suspend` 함수 안에서 일반(블로킹) 함수를 호출하면,
그 구간에서는 코루틴의 장점(양보)을 못 쓰고 스레드가 블로킹됩니다.

---

## 3. 해결법 B: Virtual Threads (가상 스레드, Java 21+)

### 핵심 개념: "블로킹해도 괜찮은 가벼운 스레드"

Java 21에서 도입된 Virtual Thread는 완전히 다른 접근입니다.
코드를 바꾸지 않아도 (블로킹 코드 그대로) 성능 문제를 해결합니다.

```
[일반 스레드 (Platform Thread)]
- OS 스레드 1개 = Java 스레드 1개
- 메모리: ~1MB per thread
- 최대 수백~수천 개

[가상 스레드 (Virtual Thread)]
- OS 스레드 1개 위에 수천 개의 가상 스레드가 올라감
- 메모리: ~수 KB per thread
- 수십만 개도 가능
- 블로킹 I/O를 만나면 자동으로 carrier thread를 양보
```

**동작 흐름:**
```
Carrier Thread (OS 스레드, 소수):

Virtual Thread 1: [처리 중] → [ES 호출 - 블로킹!] → (자동으로 carrier 양보)
Virtual Thread 2:                                    → [처리 시작] → ...
                              [ES 응답 도착] → Virtual Thread 1 다시 실행
```

**가장 큰 장점:** 기존 블로킹 코드를 그대로 써도 됩니다!

```kotlin
// 이 코드가 Virtual Thread 위에서 실행되면
// esClient.search()가 블로킹이어도 carrier thread를 양보하므로 문제 없음
fun searchSimilarProducts(): List<Product> {
    val response = esClient.search(...)  // 블로킹이지만 Virtual Thread가 알아서 처리
    return response.hits().hits().map { ... }
}
```

---

## 4. 이 프로젝트의 구조: 두 가지를 섞어 쓰는 이유

### ADR-001 결정사항: Coroutines + Virtual Threads

이 프로젝트는 **둘 다** 사용합니다:

```kotlin
// Controller: Spring MVC (블로킹 프레임워크)
@GetMapping("/{userId}")
fun getRecommendations(...): ResponseEntity<RecommendationResponse> {
    // runBlocking: 코루틴을 시작하는 진입점
    // virtualThreadDispatcher: Virtual Thread 위에서 실행하도록 지정
    val response = runBlocking(virtualThreadDispatcher) {
        // 이 블록 안은 suspend 컨텍스트 (코루틴 세계)
        recommendationService.getRecommendations(userId, limit)
    }
    return ResponseEntity.ok(response)
}
```

**왜 이렇게?**

| 레이어 | 기술 | 이유 |
|--------|------|------|
| Controller | Spring MVC (블로킹) | Spring MVC가 프로젝트 표준이라서 |
| 진입점 | `runBlocking(virtualThreadDispatcher)` | 블로킹 → 코루틴 브릿지, Virtual Thread에서 실행 |
| Service | `suspend fun` | Redis 같은 리액티브 클라이언트와 호환 위해 |
| Repository | 일부 suspend, 일부 일반 | ES 클라이언트가 블로킹 API이므로 |

---

## 5. "이슈"의 실체: 왜 문제가 되고, 왜 안 되는가?

### 현재 코드의 호출 흐름

```
Controller
  └→ runBlocking(virtualThreadDispatcher)     ← Virtual Thread 위에서 실행
       └→ getRecommendations() [suspend]
            └→ executeRecommendation() [suspend]
                 ├→ userPreferenceRepository.get() [suspend]
                 │     └→ Redis 호출 (awaitSingleOrNull) ← suspend로 양보
                 │
                 ├→ userBehaviorRepository.getRecentViewedProducts() [일반 fun]  ★
                 │     └→ ES 호출 (블로킹)  ← suspend 아님, 스레드 블로킹
                 │
                 └→ searchSimilarProducts() [일반 fun]  ★
                       └→ ES 호출 (블로킹)  ← suspend 아님, 스레드 블로킹
```

### ★ 표시된 부분이 "이슈"

suspend 컨텍스트 안에서 블로킹 함수를 호출하면:

```
이론적 문제:
- 코루틴이 "여기서 양보할 수 있어"라고 기대하지만
- 실제로는 블로킹되어 스레드를 점유함
- 만약 일반 스레드풀(Dispatchers.Default 등)이었다면 스레드 고갈 가능
```

### 하지만 이 프로젝트에서는 괜찮은 이유

```
실제 상황:
- virtualThreadDispatcher 위에서 실행 중
- Virtual Thread는 블로킹 I/O를 만나면 자동으로 carrier thread 양보
- 따라서 esClient.search()가 블로킹이어도 다른 Virtual Thread 실행 가능
- 성능 영향: 사실상 없음
```

**비유로 설명하면:**

| 상황 | 비유 |
|------|------|
| 일반 스레드 + 블로킹 | 택시 1대가 손님 기다리며 주차장에서 대기. 다른 손님 못 태움 |
| 일반 스레드 + suspend | 택시 1대가 손님 기다리는 동안 다른 손님 태우러 감 |
| Virtual Thread + 블로킹 | 택시가 수만 대. 한 대가 대기해도 다른 택시가 처리. 사실상 문제 없음 |
| Virtual Thread + suspend | 위와 같음. 추가 최적화지만 체감 차이 거의 없음 |

---

## 6. 수정한다면 어떻게 해야 하는가?

### 방법 1: 그대로 두기 (현재 권장)

Virtual Thread가 블로킹을 처리하므로 실질적 이점 없음.
코드 변경 없이 안정적 운영 가능.

### 방법 2: withContext(Dispatchers.IO)로 감싸기

```kotlin
private suspend fun searchSimilarProducts(...): List<ProductRecommendation> {
    return withContext(Dispatchers.IO) {
        // 블로킹 코드를 IO 전용 스레드풀에서 실행
        esClient.search(...)
    }
}
```

**문제점:** 이미 Virtual Thread 위에서 도는데, Dispatchers.IO (일반 스레드풀)로
다시 전환하면 오히려 Virtual Thread의 장점을 잃습니다.

### 방법 3: ES 리액티브 클라이언트 사용

```kotlin
// 가상의 코드 - ES 공식 클라이언트는 리액티브를 직접 지원하지 않음
private suspend fun searchSimilarProducts(...): List<ProductRecommendation> {
    return esReactiveClient.search(...).awaitSingle()
}
```

**문제점:** Elasticsearch Java Client는 블로킹 API만 제공.
리액티브로 바꾸려면 별도 래퍼가 필요하고 복잡도만 올라감.

---

## 7. 결론

| 항목 | 판단 |
|------|------|
| 실제 성능 문제? | **없음** — Virtual Thread가 블로킹을 커버 |
| 코드 컨벤션 위반? | **경미** — suspend 안에서 블로킹 호출은 안티패턴이지만, Virtual Thread 환경에서는 허용됨 |
| 수정 필요? | **선택사항** — 수정해도 성능 향상 없고, 오히려 복잡도만 증가할 수 있음 |
| ADR-001 준수? | **준수** — ADR-001이 Coroutines + Virtual Threads 조합을 명시하고, 이 조합에서는 블로킹 I/O가 허용됨 |

### 이 프로젝트에서의 가이드라인

```
1. Redis 호출 → suspend 사용 (Spring Data Redis Reactive가 suspend 지원)
2. ES 호출 → 블로킹 OK (ES 클라이언트가 블로킹만 지원 + Virtual Thread가 커버)
3. HTTP 호출 → suspend 사용 (WebClient가 suspend 지원)
4. 모든 것이 virtualThreadDispatcher 위에서 실행되므로 블로킹 I/O도 안전
```
