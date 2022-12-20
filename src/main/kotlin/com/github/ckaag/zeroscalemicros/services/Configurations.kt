package com.github.ckaag.zeroscalemicros.services

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "zsm")
data class ZSMConfig(
    val overwrites: List<ZOverwrite> = listOf(),
    val services: List<ZService> = listOf(),
) {
    fun getServiceConfig(serviceName: ServiceName) = services.find { it.name == serviceName }
        ?: throw Exception("Service not configured but asked for: $serviceName")

    @Suppress("unused")
    fun getServiceConfig(serviceName: String) = services.find { it.name.name == serviceName }
        ?: throw Exception("Service not configured but asked for: $serviceName")

    fun getOverwrittenWithExternal(name: ServiceName): RedirectTarget? {
        return this.overwrites.find { it.name == name }?.asRedirectTarget()
    }
}

data class ZOverwrite(val name: ServiceName, val port: Int? = null, val host: String? = null) {
    fun asRedirectTarget(): RedirectTarget = RedirectTarget(host ?: "host.testcontainers.internal", port ?: 8080)
}

@JvmInline
value class ServiceName(val name: String)

@JvmInline
value class DockerImageId(val imageAndTag: String) {
}

data class ZService(
    val name: ServiceName,
    val env: Map<String, String> = mapOf(),
    val profile: String = "",
    val image: DockerImageId? = null,
    val dockerfile: String? = null,
    val internalPort: UShort = 8080u
) {
    fun isDockerfile(): Boolean = dockerfile != null

    init {
        if (image == null && dockerfile == null) {
            throw IllegalArgumentException("Either define an image name or a path to a dockerfile for service '$name' in your configuration.")
        }
    }
}