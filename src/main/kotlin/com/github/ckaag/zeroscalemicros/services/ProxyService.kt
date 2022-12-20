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
        val redirectHost =
            config.getOverwrittenWithExternal(zService.name) ?: dockerRegistryService.waitForService(zService.name)

        val client = HttpClient.newBuilder().build()

        var request = HttpRequest.newBuilder()
            .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
            .uri(URI.create("http://host.testcontainers.internal:${redirectHost.port}$path"))

        val skipHeaders = setOf("Host", "Content-Length", "Connection")
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

            registerServiceInEureka(service, port)

            port += 1
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
                    ctx.path() + (ctx.queryString()?.let { "?$it" } ?: ""),
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
        @Suppress("SpellCheckingInspection")
        val json = """{
        "instanceId": "${service.name.name}-1",
        "app": "${service.name.name}",
        "ipAddr": "127.0.0.1",
        "hostName": "host.testcontainers.internal",
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
        "homePageUrl": "http://host.testcontainers.internal:$port",
        "statusPageUrl": "http://host.testcontainers.internal:$port/actuator/status",
        "healthCheckUrl": "http://host.testcontainers.internal:$port/actuator/health",
        "secureHealthCheckUrl": "http://host.testcontainers.internal:$port/actuator/health",
        "secureVipAddress": "http://host.testcontainers.internal:$port/actuator/svip",
        "vipAddress": "http://host.testcontainers.internal:$port/actuator/vip",
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
                unsupported()
            }

            override fun getAWSSecretKey(): String {
                unsupported()
            }

            override fun getEIPBindRebindRetries(): Int {
                unsupported()
            }

            override fun getEIPBindingRetryIntervalMsWhenUnbound(): Int {
                unsupported()
            }

            override fun getEIPBindingRetryIntervalMs(): Int {
                unsupported()
            }

            override fun shouldEnableSelfPreservation(): Boolean {
                unsupported()
            }

            override fun getRenewalPercentThreshold(): Double {
                unsupported()
            }

            override fun getRenewalThresholdUpdateIntervalMs(): Int {
                unsupported()
            }

            override fun getExpectedClientRenewalIntervalSeconds(): Int {
                unsupported()
            }

            override fun getPeerEurekaNodesUpdateIntervalMs(): Int = 1

            override fun shouldEnableReplicatedRequestCompression(): Boolean {
                unsupported()
            }

            override fun getNumberOfReplicationRetries(): Int {
                unsupported()
            }

            override fun getPeerEurekaStatusRefreshTimeIntervalMs(): Int {
                unsupported()
            }

            override fun getWaitTimeInMsWhenSyncEmpty(): Int {
                unsupported()
            }

            override fun getPeerNodeConnectTimeoutMs(): Int {
                unsupported()
            }

            override fun getPeerNodeReadTimeoutMs(): Int {
                unsupported()
            }

            override fun getPeerNodeTotalConnections(): Int {
                unsupported()
            }

            override fun getPeerNodeTotalConnectionsPerHost(): Int {
                unsupported()
            }

            override fun getPeerNodeConnectionIdleTimeoutSeconds(): Int {
                unsupported()
            }

            override fun getRetentionTimeInMSInDeltaQueue(): Long {
                unsupported()
            }

            override fun getDeltaRetentionTimerIntervalInMs(): Long {
                unsupported()
            }

            override fun getEvictionIntervalTimerInMs(): Long {
                unsupported()
            }

            override fun shouldUseAwsAsgApi(): Boolean {
                unsupported()
            }

            override fun getASGQueryTimeoutMs(): Int {
                unsupported()
            }

            override fun getASGUpdateIntervalMs(): Long {
                unsupported()
            }

            override fun getASGCacheExpiryTimeoutMs(): Long {
                unsupported()
            }

            override fun getResponseCacheAutoExpirationInSeconds(): Long {
                unsupported()
            }

            override fun getResponseCacheUpdateIntervalMs(): Long {
                unsupported()
            }

            override fun shouldUseReadOnlyResponseCache(): Boolean {
                unsupported()
            }

            override fun shouldDisableDelta(): Boolean {
                unsupported()
            }

            override fun getMaxIdleThreadInMinutesAgeForStatusReplication(): Long {
                unsupported()
            }

            override fun getMinThreadsForStatusReplication(): Int {
                unsupported()
            }

            override fun getMaxThreadsForStatusReplication(): Int = 1

            override fun getMaxElementsInStatusReplicationPool(): Int = 1

            override fun shouldSyncWhenTimestampDiffers(): Boolean {
                unsupported()
            }

            override fun getRegistrySyncRetries(): Int {
                unsupported()
            }

            override fun getRegistrySyncRetryWaitMs(): Long {
                unsupported()
            }

            override fun getMaxElementsInPeerReplicationPool(): Int = 1

            override fun getMaxIdleThreadAgeInMinutesForPeerReplication(): Long {
                unsupported()
            }

            override fun getMinThreadsForPeerReplication(): Int {
                unsupported()
            }

            override fun getMaxThreadsForPeerReplication(): Int = 1

            override fun getHealthStatusMinNumberOfAvailablePeers(): Int {
                unsupported()
            }

            override fun getMaxTimeForReplication(): Int = 1

            override fun shouldPrimeAwsReplicaConnections(): Boolean {
                unsupported()
            }

            override fun shouldDisableDeltaForRemoteRegions(): Boolean {
                unsupported()
            }

            override fun getRemoteRegionConnectTimeoutMs(): Int {
                unsupported()
            }

            override fun getRemoteRegionReadTimeoutMs(): Int {
                unsupported()
            }

            override fun getRemoteRegionTotalConnections(): Int {
                unsupported()
            }

            override fun getRemoteRegionTotalConnectionsPerHost(): Int {
                unsupported()
            }

            override fun getRemoteRegionConnectionIdleTimeoutSeconds(): Int {
                unsupported()
            }

            override fun shouldGZipContentFromRemoteRegion(): Boolean {
                unsupported()
            }

            override fun getRemoteRegionUrlsWithName(): MutableMap<String, String> {
                unsupported()
            }

            @Deprecated("Deprecated in Java")
            override fun getRemoteRegionUrls(): Array<String> {
                unsupported()
            }

            override fun getRemoteRegionAppWhitelist(regionName: String?): MutableSet<String> {
                unsupported()
            }

            override fun getRemoteRegionRegistryFetchInterval(): Int {
                unsupported()
            }

            override fun getRemoteRegionFetchThreadPoolSize(): Int {
                unsupported()
            }

            override fun getRemoteRegionTrustStore(): String {
                unsupported()
            }

            override fun getRemoteRegionTrustStorePassword(): String {
                unsupported()
            }

            override fun disableTransparentFallbackToOtherRegion(): Boolean {
                unsupported()
            }

            override fun shouldBatchReplication(): Boolean {
                unsupported()
            }

            override fun getMyUrl() = "http://host.testcontainers.internal:8080"

            override fun shouldLogIdentityHeaders(): Boolean {
                unsupported()
            }

            override fun isRateLimiterEnabled(): Boolean {
                unsupported()
            }

            override fun isRateLimiterThrottleStandardClients(): Boolean {
                unsupported()
            }

            override fun getRateLimiterPrivilegedClients(): MutableSet<String> {
                unsupported()
            }

            override fun getRateLimiterBurstSize(): Int {
                unsupported()
            }

            override fun getRateLimiterRegistryFetchAverageRate(): Int {
                unsupported()
            }

            override fun getRateLimiterFullFetchAverageRate(): Int {
                unsupported()
            }

            override fun getListAutoScalingGroupsRoleName(): String {
                unsupported()
            }

            override fun getJsonCodecName(): String {
                unsupported()
            }

            override fun getXmlCodecName(): String {
                unsupported()
            }

            override fun getBindingStrategy(): AwsBindingStrategy {
                unsupported()
            }

            override fun getRoute53DomainTTL(): Long {
                unsupported()
            }

            override fun getRoute53BindRebindRetries(): Int {
                unsupported()
            }

            override fun getRoute53BindingRetryIntervalMs(): Int {
                unsupported()
            }

            override fun getExperimental(name: String?): String {
                unsupported()
            }

            override fun getInitialCapacityOfResponseCache(): Int {
                unsupported()
            }
        }
        instanceRegistry.init(object : PeerEurekaNodes(
            null,
            serverConfig, eurekaClientConfigBean, null, applicationInfoManager
        ) {
            override fun createPeerEurekaNode(peerEurekaNodeUrl: String?): PeerEurekaNode {
                val url = "http://host.testcontainers.internal:8761/eureka"
                return PeerEurekaNode(
                    instanceRegistry,
                    "host.testcontainers.internal",
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

    private fun unsupported(): Nothing {
        throw UnsupportedOperationException()
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