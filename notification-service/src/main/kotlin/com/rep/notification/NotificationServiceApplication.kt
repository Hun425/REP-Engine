package com.rep.notification

import com.rep.notification.config.NotificationProperties
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

private val log = KotlinLogging.logger {}

@SpringBootApplication
@EnableConfigurationProperties(NotificationProperties::class)
class NotificationServiceApplication

fun main(args: Array<String>) {
    log.info { "Starting Notification Service..." }
    runApplication<NotificationServiceApplication>(*args)
}
