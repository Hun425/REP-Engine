# 기술 결정 기록 (ADR): 동시성 처리 전략

본 문서는 REP-Engine 프로젝트에서 Kotlin Coroutines와 Java 25 Virtual Threads를 혼합하여 사용하는 기술적 배경과 이유를 기록합니다.

## 1. 배경 (Context)

실시간 추천 엔진은 다음 두 가지 상반된 요구사항을 동시에 충족해야 합니다.

1. **높은 동시성 (High Concurrency):** 수만 명의 가상 유저 시뮬레이션 및 초당 수만 건의 이벤트 처리.

2. **복잡한 비즈니스 로직 (Complex Logic):** 이벤트 가공, 필터링, 추천 쿼리 등 가독성 있는 비동기 코드 필요.


## 2. 기술 비교: Coroutines vs Virtual Threads

| 항목 | Kotlin Coroutines | Java 25 Virtual Threads |

| 추상화 수준 | 언어 수준 (Syntactic Sugar) | JVM 수준 (Runtime Improvement) |

| 주 장점 | 구조적 동시성, 비동기 코드의 동기적 표현, Flow/Channel 등 풍부한 라이브러리. | Blocking I/O를 Non-blocking처럼 처리, 기존 자바 라이브러리와의 완벽한 호환성. |

| 제어 방식 | 협력적 멀티태스킹 (Cooperative) | 선점형 멀티태스킹 (Preemptive - JVM 관리) |

## 3. 왜 둘을 같이 쓰는가? (The Synergy)

### 3.1 역할의 분리 (Separation of Concerns)

- **Coroutines (The "How"):** 비동기 로직의 **흐름(Flow)**을 관리합니다. `async/await`, `Flow`를 통해 복잡한 이벤트 스트림을 우아하게 처리합니다.

- **Virtual Threads (The "Where"):** 실제 작업을 **실행(Execution)**하는 엔진 역할을 합니다. 코루틴이 실행되는 `Dispatcher`의 기반 스레드로 가상 스레드를 할당합니다.


### 3.2 I/O Blocking 라이브러리 대응

많은 실무 라이브러리(Kafka Client 일부, 구형 DB Driver 등)가 여전히 Blocking 호출을 기반으로 합니다.

- **코루틴만 쓸 때:** Blocking 호출 시 해당 코루틴이 할당된 플랫폼 스레드가 차단되어 성능이 저하됩니다.

- **가상 스레드와 결합 시:** 코루틴 내부에서 Blocking 호출이 발생해도 JVM이 가상 스레드를 언마운트(Unmount)하므로 시스템 전체의 처리량(Throughput)이 유지됩니다.


### 3.3 구조적 동시성 (Structured Concurrency)

Java 25의 `StructuredTaskScope`와 코루틴의 `CoroutineScope`를 결합하면, 수많은 가상 유저의 생명주기를 안전하게 관리할 수 있습니다. 예를 들어, 시뮬레이터 종료 시 수만 개의 가상 스레드와 코루틴을 유실 없이 안전하게 중단시킬 수 있습니다.

## 4. 실제 적용 설계 (Implementation Design)

### 4.1 Custom Dispatcher 설정

Kotlin 코루틴이 Java 25의 가상 스레드 위에서 동작하도록 전용 디스패처를 구성합니다.

```
// Java 25 가상 스레드 실행기를 기반으로 한 코루틴 디스패처
val virtualThreadDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

// 코루틴의 가독성과 가상 스레드의 성능을 동시에 확보
scope.launch(virtualThreadDispatcher) {
    val result = blockingRepository.save(data) // Blocking I/O도 가볍게 처리
}

```

### 4.2 시뮬레이터 최적화

- **Simulator:** 10만 명의 유저 세션을 가상 스레드로 띄워 메모리 점유를 최소화합니다.

- **Consumer:** 대량의 이벤트를 처리할 때 코루틴의 `Channel`을 사용해 배압(Backpressure)을 조절하고, 실제 ES 인덱싱 I/O는 가상 스레드에서 실행합니다.


## 5. 기대 효과 (Consequences)

1. **리소스 효율성:** 수만 개의 스레드를 생성해도 OS 레벨의 부하가 거의 없으며 메모리 사용량이 극히 낮음.

2. **코드 가독성:** 리액티브 프로그래밍(Mono/Flux)의 복잡함 없이 단순한 명령형 코드로 고성능 비동기 시스템 구축.

3. **운영 안정성:** Java 25의 최신 GC(ZGC)와 가상 스레드 시너지를 통해 GC Stop-the-world로 인한 지연 시간(Latency) 최소화.