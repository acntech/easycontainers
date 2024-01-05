package no.acntech.easycontainers.spring_boot_example.service

import no.acntech.easycontainers.Container
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Service
class ContainerService(
    private val container: Container,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.info("ContainerService created with container: $container")
    }

    fun stop() {
        container.stop()
    }

    fun delete() {
        container.remove()
    }

    fun getIndexPage(): String {
        val host: String? = container.getHost().also {
            log.info("Container host: $it")
        }
        return fetchWebPage("http://$host/index.html")
    }

    private fun fetchWebPage(url: String): String {
        log.trace("Fetching web page: $url")
        try {
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .uri(URI.create(url))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            val body = response.body();

            if (response.statusCode() == 200) {
                println("Content of the web page:\n${body}")
            } else {
                println("Failed to fetch the web page. Status code: ${response.statusCode()}")
            }
            return body
        } catch (e: Exception) {
            log.error("Failed to fetch web page", e)
        }
        return ""
    }

}

// http://alpine-test-service.test.svc.cluster.local/index.html