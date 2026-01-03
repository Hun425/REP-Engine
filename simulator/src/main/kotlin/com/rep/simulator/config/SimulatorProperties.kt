package com.rep.simulator.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "simulator")
data class SimulatorProperties(
    val userCount: Int = 100,
    val delayMillis: Long = 1000,
    val topic: String = "user.action.v1",
    val enabled: Boolean = true
)
