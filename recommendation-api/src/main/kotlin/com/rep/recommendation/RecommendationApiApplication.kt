package com.rep.recommendation

import mu.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class RecommendationApiApplication

private val log = KotlinLogging.logger {}

fun main(args: Array<String>) {
    runApplication<RecommendationApiApplication>(*args)
    log.info { "Recommendation API Application started" }
}
