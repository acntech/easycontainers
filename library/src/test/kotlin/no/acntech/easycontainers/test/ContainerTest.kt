package no.acntech.easycontainers.test

import no.acntech.easycontainers.ContainerFactory
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ContainerTest {

    @Test
    fun `Test Lighthttpd container`() {
        val container = ContainerFactory.kubernetesContainer {
            withName("alpine-test")
            withNamespace("test")
            withImage("localhost:5000/alpine-simple-httpd:latest")
            withExposedPort("http", 80)
            withPortMapping(80, 30080)
            withIsEphemeral(true)
            withLogLineCallback { line -> println("HTTPD-OUTPUT: $line") }
        }.build()
        container.start()
        TimeUnit.SECONDS.sleep(120)
        container.stop()
        container.remove()
    }

    @Test
    fun `Test Elasticsearch container`() {
        val container = ContainerFactory.kubernetesContainer {
            withName("elasticsearch-test")
            withNamespace("test")
            withImage("docker.elastic.co/elasticsearch/elasticsearch:8.11.3")
            withEnv("discovery.type", "single-node")
            withEnv("xpack.security.enabled", "false")
            withEnv("xpack.security.http.ssl.enabled", "false")
            withEnv("xpack.security.transport.ssl.enabled", "false")
            withEnv("CLUSTER_NAME", "dev-cluster")
            withEnv("NODE_NAME", "dev-node")
            withEnv("ELASTIC_PASSWORD", "passwd")
            withEnv("ES_JAVA_OPTS", "-Xms1024m -Xmx1024m")
            withEnv("ES_DEV_MODE", "true")
            withEnv("ES_LOG_LEVEL", "DEBUG")
            withExposedPort("http", 9200)
            withExposedPort("transport", 9300)
            withPortMapping(9200, 30200)
            withPortMapping(9300, 30300)
            withIsEphemeral(true)
            withLogLineCallback { line -> println("ELASTIC-OUTPUT: $line") }
        }.build()
        container.start()
        TimeUnit.SECONDS.sleep(120)
        container.stop()
        container.remove()
    }

}