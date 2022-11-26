package com.github.ckaag.zeroscalemicros

import com.github.ckaag.zeroscalemicros.services.ProxyService
import com.github.ckaag.zeroscalemicros.services.ServiceName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration


@Suppress("SameParameterValue")
@SpringBootTest
class ZeroScaleIntegrationTests {

    @Autowired
    var proxyService: ProxyService? = null

    private fun get(port: Int, path: String = ""): String = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .build().send(
            HttpRequest.newBuilder().GET().uri(URI.create("http://localhost:$port$path"))
                .timeout(Duration.ofSeconds(180)).build(),
            HttpResponse.BodyHandlers.ofString()
        ).body()

    @Test
    fun shouldRedirectToEchoServer() {
        assertEquals(
            "<!DOCTYPE html>\n" +
                    "<html>\n" +
                    "<head>\n" +
                    "<title>Welcome to nginx!</title>\n" +
                    "<style>\n" +
                    "html { color-scheme: light dark; }\n" +
                    "body { width: 35em; margin: 0 auto;\n" +
                    "font-family: Tahoma, Verdana, Arial, sans-serif; }\n" +
                    "</style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "<h1>Welcome to nginx!</h1>\n" +
                    "<p>If you see this page, the nginx web server is successfully installed and\n" +
                    "working. Further configuration is required.</p>\n" +
                    "\n" +
                    "<p>For online documentation and support please refer to\n" +
                    "<a href=\"http://nginx.org/\">nginx.org</a>.<br/>\n" +
                    "Commercial support is available at\n" +
                    "<a href=\"http://nginx.com/\">nginx.com</a>.</p>\n" +
                    "\n" +
                    "<p><em>Thank you for using nginx.</em></p>\n" +
                    "</body>\n" +
                    "</html>\n",
            get(proxyService?.getPort(ServiceName("echo"))!!, "/index.html")
        )
    }
}