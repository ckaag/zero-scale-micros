package com.github.ckaag.zeroscalemicros.services

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.File

@ConfigurationProperties(prefix = "zsm")
data class ZSMConfig(
    var overwrites: List<ZOverwrite> = listOf(),
    var services: List<ZService> = listOf(),
    var dockerCompose: String? = null,
) {
    fun getServiceConfig(serviceName: ServiceName) = services.find { it.name == serviceName }
        ?: throw Exception("Service not configured but asked for: $serviceName")

    @Suppress("unused")
    fun getServiceConfig(serviceName: String) = services.find { it.name.name == serviceName }
        ?: throw Exception("Service not configured but asked for: $serviceName")

    fun getOverwrittenWithExternal(name: ServiceName): RedirectTarget? {
        return this.overwrites.find { it.name == name }?.asRedirectTarget()
    }

    val composeFile: File? by lazy {
        dockerCompose?.let {
            val x = File(it)
            if (x.canRead()) x else null
        }
    }
}

data class ZOverwrite(val name: ServiceName, val port: Int? = null, val host: String? = null) {
    fun asRedirectTarget(): RedirectTarget = RedirectTarget(host ?: "host.testcontainers.internal", port ?: 8080)
}

@JvmInline
value class ServiceName(val name: String)

@JvmInline
value class DockerImageId(val imageAndTag: String)

data class ZService(
    val name: ServiceName,
    val env: Map<String, String>? = null,
    val profile: String? = null,
    val image: DockerImageId? = null,
    val dockerfile: String? = null,
    val internalPort: Int? = null
) {
    fun isDockerfile(): Boolean = dockerfile != null

    init {
        if (image == null && dockerfile == null) {
            throw IllegalArgumentException("Either define an image name or a path to a dockerfile for service '$name' in your configuration.")
        }
    }
}