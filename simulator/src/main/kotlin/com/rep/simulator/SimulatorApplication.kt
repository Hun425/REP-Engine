package com.rep.simulator

import com.rep.simulator.config.SimulatorProperties
import com.rep.simulator.service.TrafficSimulator
import mu.KotlinLogging
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.event.ContextClosedEvent
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@SpringBootApplication
@EnableConfigurationProperties(SimulatorProperties::class)
class SimulatorApplication {

    @Bean
    fun run(
        simulator: TrafficSimulator,
        properties: SimulatorProperties
    ): CommandLineRunner = CommandLineRunner {
        if (properties.enabled) {
            log.info { "=".repeat(60) }
            log.info { "REP-Engine Traffic Simulator Starting..." }
            log.info { "=".repeat(60) }
            log.info { "Configuration:" }
            log.info { "  - User Count: ${properties.userCount}" }
            log.info { "  - Delay (ms): ${properties.delayMillis}" }
            log.info { "  - Topic: ${properties.topic}" }
            log.info { "=".repeat(60) }

            simulator.startSimulation()
        } else {
            log.info { "Simulator is disabled. Set SIMULATOR_ENABLED=true to enable." }
        }
    }
}

/**
 * Spring Context 종료 시 Simulator를 정상적으로 중지합니다.
 * Runtime.addShutdownHook 대신 Spring Lifecycle을 사용하여
 * Context 종료와의 race condition을 방지합니다.
 */
@Component
class SimulatorLifecycleManager(
    private val simulator: TrafficSimulator
) : ApplicationListener<ContextClosedEvent> {
    override fun onApplicationEvent(event: ContextClosedEvent) {
        log.info { "Context closing, stopping simulator..." }
        simulator.stopSimulation()
    }
}

fun main(args: Array<String>) {
    runApplication<SimulatorApplication>(*args)
}
