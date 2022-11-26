package com.github.ckaag.zeroscalemicros

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ZeroScaleIntegrationTests {

    @Autowired
    private var webClient: WebTestClient? = null

    @BeforeEach
    fun setUp() {
        webClient = webClient?.mutate()
            ?.responseTimeout(Duration.ofMillis(180000))
            ?.build()
    }

    @Test
    fun shouldBeAvailable() {
        webClient!!.get().uri("/ok")
            .exchange().expectBody().jsonPath("\$").isEqualTo("OK")
    }

    @Test
    fun shouldRedirectToEchoServer() {
        webClient!!.get().uri("/echo/hello/world")
            .exchange().expectBody(String::class.java).value {
                it.contains("<head><title>404 Not Found</title></head>")
            }
    }
}