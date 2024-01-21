package no.acntech.easycontainers.test

import no.acntech.easycontainers.ContainerFactory
import no.acntech.easycontainers.docker.DockerRegistryUtils
import no.acntech.easycontainers.k8s.K8sContainerImageBuilder
import no.acntech.easycontainers.util.net.NetworkUtils
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.TimeUnit

class ContainerTest {

    private val log = LoggerFactory.getLogger(ContainerTest::class.java)

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
        TimeUnit.SECONDS.sleep(1000)
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

    /**
     * This test assumes that the docker file and context to build is under the ../kaniko-data/test directory. On Docker Desktop
     * for Windows, this directory should be created under /mnt/wsl/share/kaniko-data/test, and a PV and PVC must exist in k8s to
     * reflect this.
     */
    @Test
    fun `Test Kaniko image build`() {
        val imageName = "kaniko-alpine-lighttpd"
        DockerRegistryUtils.deleteImage("http://localhost:5000", imageName)

        val localIpAddress = NetworkUtils.getLocalIpAddresses().first()
        log.trace("Local IP address: {}", localIpAddress)

        val imageBuilder = K8sContainerImageBuilder()
            .withName(imageName)
            .withImageRegistry("$localIpAddress:5000")
            .withNamespace("test")
            .withDockerContextDir("test")
            .withLogLineCallback { line -> println("KANIKO-JOB-OUTPUT: ${Instant.now()} $line") }

        imageBuilder.buildImage()
    }

}