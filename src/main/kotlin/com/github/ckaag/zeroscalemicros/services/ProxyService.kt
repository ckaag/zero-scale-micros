package com.github.ckaag.zeroscalemicros.services

import org.springframework.cloud.gateway.webflux.ProxyExchange
import org.springframework.stereotype.Service

@Service
class ProxyService(private val dockerRegistryService: ContainerRegistryService, private val config: ZSMConfig) {
    suspend fun proxyIncomingRequest(proxy: ProxyExchange<ByteArray>): ProxyExchange<ByteArray> {
        val fullPath = proxy.path()
        val firstSegment = fullPath.substring(1).takeWhile { it != '/' }
        val targetPath = fullPath.substring(firstSegment.length + 1)
        val redirectHost = dockerRegistryService.waitForService(config.getServiceConfig(firstSegment).name)
        return proxy.uri("http://localhost:${redirectHost.port}$targetPath")
    }
}