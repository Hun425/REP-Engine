package com.rep.consumer

import com.rep.consumer.config.ConsumerProperties
import com.rep.consumer.config.EmbeddingProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(ConsumerProperties::class, EmbeddingProperties::class)
class BehaviorConsumerApplication

fun main(args: Array<String>) {
    runApplication<BehaviorConsumerApplication>(*args)
}
