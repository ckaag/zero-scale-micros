package com.github.ckaag.zeroscalemicros.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.appinfo.InstanceInfo
import com.netflix.eureka.cluster.PeerEurekaNode
import com.netflix.eureka.cluster.PeerEurekaNodes
import com.netflix.eureka.registry.PeerAwareInstanceRegistry
import jakarta.annotation.PostConstruct
import org.springframework.cloud.gateway.webflux.ProxyExchange
import org.springframework.stereotype.Service
import java.util.*

@Service
class ProxyService(
    private val dockerRegistryService: ContainerRegistryService,
    private val config: ZSMConfig,
    private val instanceRegistry: PeerAwareInstanceRegistry,
    private val objectMapper: ObjectMapper
) {
    suspend fun proxyIncomingRequest(proxy: ProxyExchange<ByteArray>): ProxyExchange<ByteArray> {
        val fullPath = proxy.path()
        val firstSegment = fullPath.substring(1).takeWhile { it != '/' }
        val targetPath = fullPath.substring(firstSegment.length + 1)
        val redirectHost = dockerRegistryService.waitForService(config.getServiceConfig(firstSegment).name)
        return proxy.uri("http://localhost:${redirectHost.port}$targetPath")
    }

    @PostConstruct
    fun registerServices() {
        instanceRegistry.init(object : PeerEurekaNodes(null, null, null, null, null) {
            override fun createPeerEurekaNode(peerEurekaNodeUrl: String?): PeerEurekaNode {
                TODO("Not yet implemented")
            }
        } )
        config.services.forEach { service ->
            val json = """{
    "instanceId": "${service.name.name}-1",
    "app": "${service.name.name}",
    "ipAddr": "127.0.0.1",
    "hostName": "localhost",
    "status": "UP",
    "overriddenStatus": "UNKNOWN",
    "port": {
      "${'$'}": 8080,
      "@enabled": "true"
    },
    "securePort": {
      "${'$'}": 8443,
      "@enabled": "true"
    },
    "countryId": 1,
    "leaseInfo": {
      "renewalIntervalInSecs": 3000,
      "durationInSecs": 9000,
      "registrationTimestamp": 0,
      "lastRenewalTimestamp": 0,
      "evictionTimestamp": 0,
      "serviceUpTimestamp": 0
    },
    "appGroupName": "UNKNOWN",
    "homePageUrl": "http://localhost:8080",
    "statusPageUrl": "http://localhost:8080/actuator/status",
    "healthCheckUrl": "http://localhost:8080/actuator/health",
    "secureHealthCheckUrl": "http://localhost:8080/actuator/health",
    "secureVipAddress": "http://localhost:8080/actuator/svip",
    "vipAddress": "http://localhost:8080/actuator/vip",
    "isCoordinatingDiscoveryServer": "false",
    "lastUpdatedTimestamp": "${Date().time}",
    "lastDirtyTimestamp": "${Date().time}",
    "asgName": "abc"
}"""
            val info = objectMapper.readValue(
                json, InstanceInfo::class.java
            )
            instanceRegistry.register(info, false)
        }
    }
}