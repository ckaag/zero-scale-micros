package com.github.ckaag.zeroscalemicros.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.appinfo.ApplicationInfoManager
import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.shared.transport.jersey3.EurekaJersey3Client
import com.netflix.eureka.EurekaServerConfig
import com.netflix.eureka.aws.AwsBindingStrategy
import com.netflix.eureka.cluster.PeerEurekaNode
import com.netflix.eureka.cluster.PeerEurekaNodes
import com.netflix.eureka.registry.PeerAwareInstanceRegistry
import com.netflix.eureka.transport.Jersey3ReplicationClient
import io.javalin.Javalin
import io.javalin.http.HandlerType.*
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.ws.rs.client.Client
import org.slf4j.LoggerFactory
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean
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
    private val applicationInfoManager: ApplicationInfoManager,
    private val eurekaClientConfigBean: EurekaClientConfigBean,
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
        initializeEurekaServiceRegistry()
        var port = 9000
        config.services.forEach { service ->

            port = buildScaleToZeroProxyInstance(service, port)

            port += 1

            registerServiceInEureka(service, port)
        }
    }

    private fun buildScaleToZeroProxyInstance(service: ZService, port: Int): Int {
        var port1 = port
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
        port1 = findFreePort(port1)
        facadeAppForService.start(port1)

        this.apps[service.name] = facadeAppForService
        return port1
    }

    private fun registerServiceInEureka(service: ZService, port: Int) {
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
        "dataCenterInfo": {
            "@class":"com.netflix.appinfo.MyDataCenterInfo",
            "name":"MyOwn"
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

    private fun initializeEurekaServiceRegistry() {
        val serverConfig = object : EurekaServerConfig {
            override fun getAWSAccessId(): String {
                TODO("Not yet implemented")
            }

            override fun getAWSSecretKey(): String {
                TODO("Not yet implemented")
            }

            override fun getEIPBindRebindRetries(): Int {
                TODO("Not yet implemented")
            }

            override fun getEIPBindingRetryIntervalMsWhenUnbound(): Int {
                TODO("Not yet implemented")
            }

            override fun getEIPBindingRetryIntervalMs(): Int {
                TODO("Not yet implemented")
            }

            override fun shouldEnableSelfPreservation(): Boolean {
                TODO("Not yet implemented")
            }

            override fun getRenewalPercentThreshold(): Double {
                TODO("Not yet implemented")
            }

            override fun getRenewalThresholdUpdateIntervalMs(): Int {
                TODO("Not yet implemented")
            }

            override fun getExpectedClientRenewalIntervalSeconds(): Int {
                TODO("Not yet implemented")
            }

            override fun getPeerEurekaNodesUpdateIntervalMs(): Int = 1

            override fun shouldEnableReplicatedRequestCompression(): Boolean {
                TODO("Not yet implemented")
            }

            override fun getNumberOfReplicationRetries(): Int {
                TODO("Not yet implemented")
            }

            override fun getPeerEurekaStatusRefreshTimeIntervalMs(): Int {
                TODO("Not yet implemented")
            }

            override fun getWaitTimeInMsWhenSyncEmpty(): Int {
                TODO("Not yet implemented")
            }

            override fun getPeerNodeConnectTimeoutMs(): Int {
                TODO("Not yet implemented")
            }

            override fun getPeerNodeReadTimeoutMs(): Int {
                TODO("Not yet implemented")
            }

            override fun getPeerNodeTotalConnections(): Int {
                TODO("Not yet implemented")
            }

            override fun getPeerNodeTotalConnectionsPerHost(): Int {
                TODO("Not yet implemented")
            }

            override fun getPeerNodeConnectionIdleTimeoutSeconds(): Int {
                TODO("Not yet implemented")
            }

            override fun getRetentionTimeInMSInDeltaQueue(): Long {
                TODO("Not yet implemented")
            }

            override fun getDeltaRetentionTimerIntervalInMs(): Long {
                TODO("Not yet implemented")
            }

            override fun getEvictionIntervalTimerInMs(): Long {
                TODO("Not yet implemented")
            }

            override fun shouldUseAwsAsgApi(): Boolean {
                TODO("Not yet implemented")
            }

            override fun getASGQueryTimeoutMs(): Int {
                TODO("Not yet implemented")
            }

            override fun getASGUpdateIntervalMs(): Long {
                TODO("Not yet implemented")
            }

            override fun getASGCacheExpiryTimeoutMs(): Long {
                TODO("Not yet implemented")
            }

            override fun getResponseCacheAutoExpirationInSeconds(): Long {
                TODO("Not yet implemented")
            }

            override fun getResponseCacheUpdateIntervalMs(): Long {
                TODO("Not yet implemented")
            }

            override fun shouldUseReadOnlyResponseCache(): Boolean {
                TODO("Not yet implemented")
            }

            override fun shouldDisableDelta(): Boolean {
                TODO("Not yet implemented")
            }

            override fun getMaxIdleThreadInMinutesAgeForStatusReplication(): Long {
                TODO("Not yet implemented")
            }

            override fun getMinThreadsForStatusReplication(): Int {
                TODO("Not yet implemented")
            }

            override fun getMaxThreadsForStatusReplication(): Int = 1

            override fun getMaxElementsInStatusReplicationPool(): Int = 1

            override fun shouldSyncWhenTimestampDiffers(): Boolean {
                TODO("Not yet implemented")
            }

            override fun getRegistrySyncRetries(): Int {
                TODO("Not yet implemented")
            }

            override fun getRegistrySyncRetryWaitMs(): Long {
                TODO("Not yet implemented")
            }

            override fun getMaxElementsInPeerReplicationPool(): Int = 1

            override fun getMaxIdleThreadAgeInMinutesForPeerReplication(): Long {
                TODO("Not yet implemented")
            }

            override fun getMinThreadsForPeerReplication(): Int {
                TODO("Not yet implemented")
            }

            override fun getMaxThreadsForPeerReplication(): Int = 1

            override fun getHealthStatusMinNumberOfAvailablePeers(): Int {
                TODO("Not yet implemented")
            }

            override fun getMaxTimeForReplication(): Int = 1

            override fun shouldPrimeAwsReplicaConnections(): Boolean {
                TODO("Not yet implemented")
            }

            override fun shouldDisableDeltaForRemoteRegions(): Boolean {
                TODO("Not yet implemented")
            }

            override fun getRemoteRegionConnectTimeoutMs(): Int {
                TODO("Not yet implemented")
            }

            override fun getRemoteRegionReadTimeoutMs(): Int {
                TODO("Not yet implemented")
            }

            override fun getRemoteRegionTotalConnections(): Int {
                TODO("Not yet implemented")
            }

            override fun getRemoteRegionTotalConnectionsPerHost(): Int {
                TODO("Not yet implemented")
            }

            override fun getRemoteRegionConnectionIdleTimeoutSeconds(): Int {
                TODO("Not yet implemented")
            }

            override fun shouldGZipContentFromRemoteRegion(): Boolean {
                TODO("Not yet implemented")
            }

            override fun getRemoteRegionUrlsWithName(): MutableMap<String, String> {
                TODO("Not yet implemented")
            }

            @Deprecated("Deprecated in Java")
            override fun getRemoteRegionUrls(): Array<String> {
                TODO("Not yet implemented")
            }

            override fun getRemoteRegionAppWhitelist(regionName: String?): MutableSet<String> {
                TODO("Not yet implemented")
            }

            override fun getRemoteRegionRegistryFetchInterval(): Int {
                TODO("Not yet implemented")
            }

            override fun getRemoteRegionFetchThreadPoolSize(): Int {
                TODO("Not yet implemented")
            }

            override fun getRemoteRegionTrustStore(): String {
                TODO("Not yet implemented")
            }

            override fun getRemoteRegionTrustStorePassword(): String {
                TODO("Not yet implemented")
            }

            override fun disableTransparentFallbackToOtherRegion(): Boolean {
                TODO("Not yet implemented")
            }

            override fun shouldBatchReplication(): Boolean {
                TODO("Not yet implemented")
            }

            override fun getMyUrl() = "http://localhost:8080"

            override fun shouldLogIdentityHeaders(): Boolean {
                TODO("Not yet implemented")
            }

            override fun isRateLimiterEnabled(): Boolean {
                TODO("Not yet implemented")
            }

            override fun isRateLimiterThrottleStandardClients(): Boolean {
                TODO("Not yet implemented")
            }

            override fun getRateLimiterPrivilegedClients(): MutableSet<String> {
                TODO("Not yet implemented")
            }

            override fun getRateLimiterBurstSize(): Int {
                TODO("Not yet implemented")
            }

            override fun getRateLimiterRegistryFetchAverageRate(): Int {
                TODO("Not yet implemented")
            }

            override fun getRateLimiterFullFetchAverageRate(): Int {
                TODO("Not yet implemented")
            }

            override fun getListAutoScalingGroupsRoleName(): String {
                TODO("Not yet implemented")
            }

            override fun getJsonCodecName(): String {
                TODO("Not yet implemented")
            }

            override fun getXmlCodecName(): String {
                TODO("Not yet implemented")
            }

            override fun getBindingStrategy(): AwsBindingStrategy {
                TODO("Not yet implemented")
            }

            override fun getRoute53DomainTTL(): Long {
                TODO("Not yet implemented")
            }

            override fun getRoute53BindRebindRetries(): Int {
                TODO("Not yet implemented")
            }

            override fun getRoute53BindingRetryIntervalMs(): Int {
                TODO("Not yet implemented")
            }

            override fun getExperimental(name: String?): String {
                TODO("Not yet implemented")
            }

            override fun getInitialCapacityOfResponseCache(): Int {
                TODO("Not yet implemented")
            }
        }
        instanceRegistry.init(object : PeerEurekaNodes(
            null,
            serverConfig, eurekaClientConfigBean, null, applicationInfoManager
        ) {
            override fun createPeerEurekaNode(peerEurekaNodeUrl: String?): PeerEurekaNode {
                val url = "http://localhost:8761/eureka"
                return PeerEurekaNode(
                    instanceRegistry,
                    "localhost",
                    url,
                    Jersey3ReplicationClient(object : EurekaJersey3Client {
                        override fun getClient(): Client? {
                            return null
                        }

                        override fun destroyResources() {
                        }
                    }, url),
                    serverConfig
                )
            }

            init {
                start()
            }
        })
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