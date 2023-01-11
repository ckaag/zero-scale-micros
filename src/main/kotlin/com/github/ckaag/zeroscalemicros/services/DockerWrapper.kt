package com.github.ckaag.zeroscalemicros.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.netflix.eureka.registry.PeerAwareInstanceRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.apache.juli.logging.LogFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.testcontainers.Testcontainers
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.OutputFrame
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

data class RedirectTarget(val host: String, val port: Int)

@Service
class ContainerRegistryService(
    private val dockerService: DockerService,
    private val clock: Clock,
    private val config: ZSMConfig
) {
    @Value("\${zsm.containerStandByInSeconds:300}")
    private lateinit var containerStandbyInSeconds: Number
    private val redirects =
        ConcurrentHashMap<ServiceName, RedirectTarget>() // contains testcontainers relevant redirection info for all running containers
    private val lastRequest =
        ConcurrentHashMap(config.services.associate { it.name to Instant.MIN }) // request is always updated by each call to the wait function

    fun waitForService(serviceName: ServiceName): RedirectTarget {
        lastRequest[serviceName] = clock.instant()
        //double scoped locking
        val red = redirects[serviceName]
        if (red != null) {
            return red
        } else {
            synchronized(config.getServiceConfig(serviceName)) {
                var redirect = redirects[serviceName]
                if (redirect != null) {
                    // early return because other service might have already started it
                    return redirect
                }
                redirect = dockerService.startService(config.getServiceConfig(serviceName))
                redirects[serviceName] = redirect
                lastRequest[serviceName] = clock.instant()
                return redirect
            }
        }
    }

    @Scheduled(fixedRate = 60_000) // kill containers after 5m
    fun stopIdleContainers() {
        lastRequest.forEach { (serviceName, lastRequest) ->
            if (lastRequest.plusSeconds(containerStandbyInSeconds.toLong()).isBefore(clock.instant())) {
                synchronized(config.getServiceConfig(serviceName)) {
                    dockerService.stopService(serviceName)
                    redirects.remove(serviceName)
                }
            }
        }
    }
}

private const val SPRING_APPLICATION_JSON = "SPRING_APPLICATION_JSON"

// NOT THREAD-SAFE AT ALL
@Service
class DockerService(
    private val instanceRegistry: PeerAwareInstanceRegistry,
    private val objectMapper: ObjectMapper,
    private val config: ZSMConfig
) {
    private val redirects = ConcurrentHashMap<ServiceName, Pair<ZService, GenericContainer<*>>>()

    private var containerLogger = LogFactory.getLog(org.testcontainers.Testcontainers::class.java)
    private var logConsumer: Consumer<OutputFrame> = Consumer<OutputFrame> { containerLogger.debug(it.utf8String) }

    @Value("\${server.port}")
    private lateinit var serverPort: Number
    private lateinit var dockerComposeContainer: DockerComposeContainer<*>

    fun startService(service: ZService): RedirectTarget {
        return if (service.isDockerfile()) {
            startServiceWithDockerfile(service)
        } else {
            startServiceWithImage(service)
        }
    }

    @PostConstruct
    fun start() {
        val compose = config.composeFile
        if (compose != null) {
            dockerComposeContainer =
                DockerComposeContainer(compose)
            dockerComposeContainer.start()
        }
    }

    @PreDestroy
    fun stop() {
        if (config.composeFile != null) {
            dockerComposeContainer.stop()
        }
    }

    private fun startServiceWithImage(service: ZService): RedirectTarget {
        val rawContainer =
            GenericContainer(
                DockerImageName.parse(service.image!!.imageAndTag)!!
            )
        var g = rawContainer
            .withExposedPorts(service.internalPort ?: 8080)
            .withEnv(collectEnv(service.env, service.profile))
        if (service.waitForRegex != null) {
            g = g.waitingFor(LogMessageWaitStrategy().withRegEx(service.waitForRegex))
        }
        g.withAccessToHost(true)
        makePortsVisible()
        g.start()
        g.followOutput(logConsumer)
        redirects[service.name] = Pair(service, g)
        return RedirectTarget(g.host, g.firstMappedPort!!)
    }

    private fun collectEnv(env: Map<String, String>?, profile: String?): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        env?.entries?.forEach {
            map[it.key] = it.value
        }

        map[SPRING_APPLICATION_JSON] = mergeJsonStrings(
            env?.get(SPRING_APPLICATION_JSON) ?: "{}",
            "{\"eureka\":{\"client\":{\"serviceUrl\":{\"defaultZone\":\"http://host.testcontainers.internal:8761/eureka\"}, \"hostname\": \"host.testcontainers.internal\"}}, \"server\":{\"port\":8080}}"
        )

        map["SPRING_PROFILES_ACTIVE"] = profile ?: ""

        return map
    }

    @Suppress("SameParameterValue")
    private fun mergeJsonStrings(a: String, b: String): String {
        return merge(objectMapper.readTree(a), objectMapper.readTree(b)).toString()
    }

    private fun merge(mainNode: JsonNode, updateNode: JsonNode): JsonNode {
        val fieldNames: Iterator<String> = updateNode.fieldNames()
        while (fieldNames.hasNext()) {
            val updatedFieldName: String = fieldNames.next()
            val valueToBeUpdated: JsonNode? = mainNode.get(updatedFieldName)
            val updatedValue: JsonNode? = updateNode.get(updatedFieldName)
            if (mainNode is ObjectNode) {
                if (valueToBeUpdated == null) {
                    mainNode.set<JsonNode>(updatedFieldName, updatedValue)
                    continue
                }
                if (updatedValue == null) {
                    mainNode.set<JsonNode>(updatedFieldName, valueToBeUpdated)
                    continue
                }
            }
            if (valueToBeUpdated!!.isArray && updatedValue!!.isArray
            ) {
                for (i in 0 until updatedValue.size()) {
                    val updatedChildNode: JsonNode = updatedValue.get(i)
                    if (valueToBeUpdated.size() <= i) {
                        (valueToBeUpdated as ArrayNode).add(updatedChildNode)
                    }
                    val childNodeToBeUpdated: JsonNode = valueToBeUpdated.get(i)
                    merge(childNodeToBeUpdated, updatedChildNode)
                }
            } else if (valueToBeUpdated.isObject) {
                merge(valueToBeUpdated, updatedValue!!)
            } else {
                if (mainNode is ObjectNode) {
                    mainNode.replace(updatedFieldName, updatedValue)
                }
            }
        }
        return mainNode
    }

    private fun startServiceWithDockerfile(service: ZService): RedirectTarget {
        val rawContainer =
            GenericContainer(
                ImageFromDockerfile().withFileFromPath(
                    ".",
                    if (service.dockerfile!!.endsWith("Dockerfile")) File(service.dockerfile).toPath().parent else File(
                        service.dockerfile
                    ).toPath()
                )
            )
        var g = rawContainer
            .withExposedPorts(service.internalPort ?: 8080)
            .withEnv(collectEnv(service.env, service.profile))
        if (service.waitForRegex != null) {
            g = g.waitingFor(LogMessageWaitStrategy().withRegEx(service.waitForRegex))
        }
        g.withAccessToHost(true)
        makePortsVisible()
        g.start()
        g.followOutput(logConsumer)
        redirects[service.name] = Pair(service, g)
        return RedirectTarget(g.host, g.firstMappedPort!!)
    }

    private fun makePortsVisible() {
        // eureka port visible
        Testcontainers.exposeHostPorts(
            serverPort.toInt(),
            *instanceRegistry.applications.registeredApplications.flatMap { it.instances }.map { it.port }.toIntArray()
        )
        //If using different network setup: Testcontainers.exposeHostPorts(serverPort.toInt(), *instanceRegistry.applications.registeredApplications.filter { zsmConfig.services.none {s -> it.name == s.name.name} || zsmConfig.overwrites.any {s -> s.name.name == it.name}  }.flatMap { it.instances }.map { it.port}.toIntArray() )
    }

    fun stopService(serviceName: ServiceName) {
        // is blocking, should be turned into non-blocking alternative to not break coroutine calling it
        redirects[serviceName]?.second?.stop()
        redirects.remove(serviceName)
    }
}