package no.acntech.easycontainers.test

import no.acntech.easycontainers.ContainerFactory
import no.acntech.easycontainers.docker.DockerRegistryUtils
import no.acntech.easycontainers.k8s.K8sContainerImageBuilder
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit

class ContainerTest {

   private val log = LoggerFactory.getLogger(ContainerTest::class.java)

    companion object {
        private const val REGISTRY = "172.23.75.43:5000"
    }

   private val dockerfileContent = """       
        FROM alpine:latest     
        COPY log_time.sh /usr/local/bin/log_time.sh     
        RUN chmod +x /usr/local/bin/log_time.sh
        CMD sh -c "/usr/local/bin/log_time.sh"
    """.trimIndent()

   private val logTimeScriptContent = """
         #!/bin/sh
         while true; do
               echo "The time is now $(date)"
               sleep 2
         done
      """.trimIndent()

   @Test
   fun `Test Lighthttpd container`() {
      val imageName = "alpine-simple-httpd"

      val container = ContainerFactory.kubernetesContainer {
         withName("alpine-simple-httpd-test")
         withNamespace("test")
         withImage("$REGISTRY/$imageName:latest")
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
   fun `Test simple alpine container`() {
      val imageName = "simple-alpine"

      val container = ContainerFactory.kubernetesContainer {
         withName("alpine-test")
         withNamespace("test")
         withImage("$REGISTRY/$imageName:latest")
         withIsEphemeral(true)
         withLogLineCallback { line -> println("SIMPLE-ALPINE-OUTPUT: $line") }
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
    *
    */
   @Test
   fun `Test Kaniko image build`() {
      val imageName = "simple-alpine"

      DockerRegistryUtils.deleteImage("http://$REGISTRY", imageName)

      val tempDir = Files.createTempDirectory("dockercontext-").toString()
      log.debug("Temp dir for docker-context created: {}", tempDir)
      val dockerfile = File(tempDir, "Dockerfile")
      val logTimeScript = File(tempDir, "log_time.sh")
      dockerfile.writeText(dockerfileContent)
      logTimeScript.writeText(logTimeScriptContent)

      log.debug("Dockerfile created: {}", dockerfile.absolutePath)
      log.debug("log_time.sh created: {}", logTimeScript.absolutePath)

      val imageBuilder = K8sContainerImageBuilder()
         .withName(imageName)
         .withImageRegistry("http://$REGISTRY")
         .withNamespace("test")
         .withDockerContextDir(File(tempDir).absolutePath)
         .withLogLineCallback { line -> println("KANIKO-JOB-OUTPUT: ${Instant.now()} $line") }

      imageBuilder.buildImage()

      // Test deploying it
      val container = ContainerFactory.kubernetesContainer {
         withName("simple-alpine-test")
         withNamespace("test")
         withImage("$REGISTRY/$imageName:latest")
         withIsEphemeral(true)
         withLogLineCallback { line -> println("SIMPLE-ALPINE-OUTPUT: $line") }
      }.build()

      container.start()

      TimeUnit.SECONDS.sleep(120)

      container.stop()
      container.remove()
   }

}