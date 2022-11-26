package com.github.ckaag.zeroscalemicros.services

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
    private val containerMutex =
        ConcurrentHashMap(config.services.associate { it.name to Mutex() }) // only one manipulation at a time

    suspend fun waitForService(serviceName: ServiceName): RedirectTarget {
        lastRequest[serviceName] = clock.instant()
        //double scoped locking
        val red = redirects[serviceName]
        if (red != null) {
            return red
        } else {
            containerMutex[serviceName]!!.withLock {
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
        runBlocking {
            lastRequest.forEach { (serviceName, lastRequest) ->
                if (lastRequest.plusSeconds(60L).isBefore(clock.instant())) {
                    containerMutex[serviceName]!!.withLock {
                        dockerService.stopService(serviceName)
                        redirects.remove(serviceName)
                    }
                }
            }
        }
    }
}

// NOT THREAD-SAFE AT ALL
@Service
class DockerService {
    private val redirects = ConcurrentHashMap<ServiceName, Pair<ZService, GenericContainer<*>>>()
    suspend fun startService(service: ZService): RedirectTarget {
        val g = GenericContainer(service.image.asTestcontainersImageName())
            .withExposedPorts(service.internalPort.toInt())
            //.waitingFor(Wait.forHttp("/"))
        g.start()
        redirects[service.name] = Pair(service, g)
        return RedirectTarget(g.host, g.firstMappedPort!!)
    }

    suspend fun stopService(serviceName: ServiceName) {
        // is blocking, should be turned into non-blocking alternative to not break coroutine calling it
        redirects[serviceName]?.second?.stop()
        redirects.remove(serviceName)
    }
}