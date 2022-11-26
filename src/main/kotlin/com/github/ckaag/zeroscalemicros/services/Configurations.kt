package com.github.ckaag.zeroscalemicros.services

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.stereotype.Component
import org.testcontainers.utility.DockerImageName

@ConfigurationProperties(prefix = "zsm")
data class ZSMConfig(
    val services: List<ZService> = listOf()
) {
    fun getServiceConfig(serviceName: ServiceName) = services.find { it.name == serviceName }?: throw Exception("Service not configured but asked for: $serviceName")
    fun getServiceConfig(serviceName: String) = services.find { it.name.name == serviceName }?: throw Exception("Service not configured but asked for: $serviceName")
}

@JvmInline
value class ServiceName(val name: String)
@JvmInline
value class DockerImageId(val imageAndTag: String) {
    fun asTestcontainersImageName() = DockerImageName.parse(imageAndTag)!!
}
data class ZService(val name: ServiceName, val image: DockerImageId, val internalPort: UShort = 8080u)