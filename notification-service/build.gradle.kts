plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Common 모듈
    implementation(project(":common-avro"))
    implementation(project(":common-model"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.kafka:spring-kafka")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.9.0")

    // Avro & Schema Registry
    implementation("io.confluent:kafka-avro-serializer:7.5.0")

    // Elasticsearch Java API Client
    implementation("co.elastic.clients:elasticsearch-java:8.11.0")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Redis (Rate Limiter)
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    // WebClient for HTTP 호출 (recommendation-api 연동)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // ShedLock for 분산 락 (다중 인스턴스 중복 실행 방지)
    implementation("net.javacrumbs.shedlock:shedlock-spring:5.10.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-redis-spring:5.10.0")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")  // Phase 5: JSON 로깅

    // Micrometer for metrics
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Distributed Tracing (Phase 5)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
