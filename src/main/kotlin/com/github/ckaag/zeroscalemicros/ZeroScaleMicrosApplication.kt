package com.github.ckaag.zeroscalemicros

import com.github.ckaag.zeroscalemicros.services.ZSMConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.bind.annotation.*
import java.time.Clock


@SpringBootApplication
@EnableEurekaServer
@EnableScheduling
@EnableConfigurationProperties(ZSMConfig::class)
class ZeroScaleMicrosApplication {
    @Bean
    fun clock() = Clock.systemUTC()!!
}

fun main(args: Array<String>) {
    runApplication<ZeroScaleMicrosApplication>(*args)
}
