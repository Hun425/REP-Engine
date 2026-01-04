package com.rep.simulator.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "simulator")
data class SimulatorProperties(
    @field:Positive(message = "userCount must be positive")
    val userCount: Int = 100,

    @field:Positive(message = "delayMillis must be positive")
    val delayMillis: Long = 1000,

    @field:NotBlank(message = "topic must not be blank")
    val topic: String = "user.action.v1",

    val enabled: Boolean = true,

    @field:Positive(message = "productCountPerCategory must be positive")
    val productCountPerCategory: Int = 100
)
