package com.github.ckaag.zeroscalemicros.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.appinfo.InstanceInfo
import com.netflix.eureka.cluster.PeerEurekaNode
import com.netflix.eureka.cluster.PeerEurekaNodes
import com.netflix.eureka.registry.PeerAwareInstanceRegistry
import io.javalin.Javalin
import io.javalin.http.HandlerType.*
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*

@Service
class ProxyService(
    private val dockerRegistryService: ContainerRegistryService,
    private val config: ZSMConfig,
    private val instanceRegistry: PeerAwareInstanceRegistry,
    private val objectMapper: ObjectMapper
) {
    private val apps = mutableMapOf<ServiceName, Javalin>()
    fun getPort(service: ServiceName): Int? = apps[service]?.port()

    fun sendProxyRequestOut(
        method: String,
        zService: ZService,
        headers: Map<String, String>,
        path: String,
        body: ByteArray?
    ): HttpResponse<ByteArray> {
        val redirectHost = dockerRegistryService.waitForService(zService.name)

        val client = HttpClient.newBuilder().build()

        var request = HttpRequest.newBuilder()
            .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
            .uri(URI.create("http://localhost:${redirectHost.port}$path"))

        val skipHeaders = setOf("Host", "Content-Length")
        headers.filter { !skipHeaders.contains(it.key) }.forEach { (key, value) ->
            try {
                request = request.header(key, value)
            } catch (e: Exception) {
                LoggerFactory.getLogger(this.javaClass).info("Skipping header: $key = $value", e)
            }
        }

        //return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
        //return CompletableFuture.completedFuture(r);

    }

    @PreDestroy
    fun preDestroy() {
        apps.forEach { it.value.stop() }
    }

    @PostConstruct
    fun registerServices() {
        instanceRegistry.init(object : PeerEurekaNodes(null, null, null, null, null) {
            override fun createPeerEurekaNode(peerEurekaNodeUrl: String?): PeerEurekaNode {
                TODO("Not yet implemented")
            }
        })
        var port = 9000
        config.services.forEach { service ->

            val facadeAppForService = Javalin.create()
            listOf(GET, POST, PUT, PATCH, DELETE, HEAD).forEach { method ->
                facadeAppForService.addHandler(method, "/**") { ctx ->
                    val f = sendProxyRequestOut(
                        method.name,
                        service,
                        ctx.headerMap(),
                        ctx.path(),
                        ctx.bodyAsBytes()
                    )
                    // tests break when using ctx.future {f} instead here, so we go with sync for now
                    f.headers().map().forEach { (key, value) -> ctx.header(key, value.joinToString(",")) }
                    ctx.result(f.body())
                }
            }
            port = findFreePort(port)
            facadeAppForService.start(port)

            this.apps[service.name] = facadeAppForService

            port += 1

            val json = """{
    "instanceId": "${service.name.name}-1",
    "app": "${service.name.name}",
    "ipAddr": "127.0.0.1",
    "hostName": "localhost",
    "status": "UP",
    "overriddenStatus": "UNKNOWN",
    "port": {
      "${'$'}": $port,
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
    "homePageUrl": "http://localhost:$port",
    "statusPageUrl": "http://localhost:$port/actuator/status",
    "healthCheckUrl": "http://localhost:$port/actuator/health",
    "secureHealthCheckUrl": "http://localhost:$port/actuator/health",
    "secureVipAddress": "http://localhost:$port/actuator/svip",
    "vipAddress": "http://localhost:$port/actuator/vip",
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

    private fun findFreePort(startPort: Int): Int {
        for (port in startPort..9999)
            try {
                ServerSocket(port).use { serverSocket ->
                    require(serverSocket.localPort == port)
                }
                return port
            } catch (e: Exception) {
                continue
            }
        throw Exception("No free port found")
    }
}