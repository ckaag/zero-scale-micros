package com.github.ckaag.zeroscalemicros.services

import org.apache.juli.logging.LogFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.OutputFrame
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

    @Scheduled(fixedRate = 10_000)
    fun stopIdleContainers() {
        lastRequest.forEach { (serviceName, lastRequest) ->
            if (lastRequest.plusSeconds(60L).isBefore(clock.instant())) {
                synchronized(config.getServiceConfig(serviceName)) {
                    dockerService.stopService(serviceName)
                    redirects.remove(serviceName)
                }
            }
        }
    }
}

// NOT THREAD-SAFE AT ALL
@Service
class DockerService {
    private val redirects = ConcurrentHashMap<ServiceName, Pair<ZService, GenericContainer<*>>>()

    private var containerLogger = LogFactory.getLog(org.testcontainers.Testcontainers::class.java)
    private var logConsumer: Consumer<OutputFrame> = Consumer<OutputFrame> { containerLogger.debug(it.utf8String) }

    fun startService(service: ZService): RedirectTarget {
        return if (service.isDockerfile()) {
            startServiceWithDockerfile(service)
        } else {
            startServiceWithImage(service)
        }
    }

    private fun startServiceWithImage(service: ZService): RedirectTarget {
        val rawContainer =
            GenericContainer(
                DockerImageName.parse(service.image!!.imageAndTag)!!
            )
        val g = rawContainer
            .withExposedPorts(service.internalPort.toInt())
        g.start()
        g.followOutput(logConsumer)
        redirects[service.name] = Pair(service, g)
        return RedirectTarget(g.host, g.firstMappedPort!!)
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
        val g = rawContainer
            .withExposedPorts(service.internalPort.toInt())
            .withEnv("SPRING_PROFILES_ACTIVE", service.profile)
            .let {
                if (service.profile.isNotBlank()) {
                    it.withCommand()
                } else {
                    it
                }
            }
            .withEnv(service.env)
        g.start()
        g.followOutput(logConsumer)
        redirects[service.name] = Pair(service, g)
        return RedirectTarget(g.host, g.firstMappedPort!!)
    }

    fun stopService(serviceName: ServiceName) {
        // is blocking, should be turned into non-blocking alternative to not break coroutine calling it
        redirects[serviceName]?.second?.stop()
        redirects.remove(serviceName)
    }
}