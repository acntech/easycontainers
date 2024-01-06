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
            withExposedPort(80)
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
            withExposedPort(9200)
            withPortMapping(9200, 30200)
            withIsEphemeral(false)
            withConfigFile(
                name = "elasticsearch-config",
                path = "/usr/share/elasticsearch/config/elasticsearch.yml",
                content = """
                    cluster.name: "docker-cluster"
                    network.host: 0.0.0.0
                    xpack.security.enabled: false
                    xpack.security.enrollment.enabled: false
                    xpack.security.http.ssl:
                      enabled: false
                      keystore.path: certs/http.p12                    
                    xpack.security.transport.ssl:
                      enabled: false
                      verification_mode: certificate
                      keystore.path: certs/transport.p12
                      truststore.path: certs/transport.p12                    
                    cluster.initial_master_nodes: ["f659bb5a9817"]
                    """
                )
            withLogLineCallback { line -> println("ELASTIC-OUTPUT: $line") }
        }.build()
        container.start()
        TimeUnit.SECONDS.sleep(120)
        container.stop()
        container.remove()
    }


}