package com.github.ckaag.zeroscalemicros

import com.github.ckaag.zeroscalemicros.services.ProxyService
import com.github.ckaag.zeroscalemicros.services.ZSMConfig
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.cloud.gateway.webflux.ProxyExchange
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer
import org.springframework.context.annotation.Bean
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Clock


@SpringBootApplication
@EnableEurekaServer
@EnableConfigurationProperties(ZSMConfig::class)
class ZeroScaleMicrosApplication {
    @Bean
    fun clock() = Clock.systemUTC()!!
}

fun main(args: Array<String>) {
    runApplication<ZeroScaleMicrosApplication>(*args)
}


@RestController
@RequestMapping
class ZSRedirectController(private val proxyService: ProxyService) {

    @GetMapping("/ok")
    suspend fun ok() = "OK"

    //TODO: use different ports
    @GetMapping("/**")
    suspend fun proxyGet(proxy: ProxyExchange<ByteArray>): ResponseEntity<*> =
        proxyService.proxyIncomingRequest(proxy).get().awaitSingle()

    @PostMapping("/**")
    suspend fun proxyPost(proxy: ProxyExchange<ByteArray>): ResponseEntity<*> =
        proxyService.proxyIncomingRequest(proxy).post().awaitSingle()

    @PutMapping("/**")
    suspend fun proxyPut(proxy: ProxyExchange<ByteArray>): ResponseEntity<*> =
        proxyService.proxyIncomingRequest(proxy).put().awaitSingle()

    @DeleteMapping("/**")
    suspend fun proxyDelete(proxy: ProxyExchange<ByteArray>): ResponseEntity<*> =
        proxyService.proxyIncomingRequest(proxy).delete().awaitSingle()

}