package no.acntech.easycontainers.test

import no.acntech.easycontainers.ContainerFactory
import no.acntech.easycontainers.ContainerType
import no.acntech.easycontainers.ImageBuilder
import no.acntech.easycontainers.docker.DockerRegistryUtils
import no.acntech.easycontainers.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

class ContainerTests {

   private val log = LoggerFactory.getLogger(ContainerTests::class.java)

   companion object {
      private const val REGISTRY = "172.23.75.43:5000"

      private val DOCKERFILE_CONTENT = """       
        FROM alpine:latest     
        COPY log_time.sh /usr/local/bin/log_time.sh     
        RUN chmod +x /usr/local/bin/log_time.sh
        CMD sh -c "/usr/local/bin/log_time.sh"
    """.trimIndent()

      private val LOG_TIME_SCRIPT_CONTENT = """
         #!/bin/sh
         while true; do
               echo "The time is now $(date)"
               sleep 2
         done
      """.trimIndent()
   }


   @ParameterizedTest
   @ValueSource(strings = ["DOCKER"/*, "KUBERNETES"*/])
   fun `Test Lighthttpd container`(containerType: String) {
      log.info("Testing Lighthttpd container with container type: {}", containerType)

      val imageName = "alpine-simple-httpd"

      val container = ContainerFactory.container {
         withType(ContainerType.valueOf(containerType))
         withName(ContainerName.of("$imageName-test"))
         withNamespace(Namespace.TEST)
         withImage(ImageURL.of("$REGISTRY/test/$imageName:latest"))
         withExposedPort(PortMappingName.HTTP, NetworkPort.HTTP)

         // This will work for both Docker and Kubernetes since
         // NodePort is used for Kubernetes when running this test outside the cluster
         withPortMapping(NetworkPort.HTTP, NetworkPort.of(30080))

         withIsEphemeral(true)
         withLogLineCallback { line -> println("HTTPD-OUTPUT: $line") }
      }.build()

      container.run()

      TimeUnit.SECONDS.sleep(1000)

      container.stop()
      container.remove()
   }

   @Test
   fun `Test simple alpine container`() {
      val container = ContainerFactory.kubernetesContainer {
         withName(ContainerName.of("alpine-test"))
         withNamespace(Namespace.TEST)
         withImage(ImageURL.of("$REGISTRY/test/alpine-test:latest"))
         withIsEphemeral(true)
         withLogLineCallback { line -> println("SIMPLE-ALPINE-OUTPUT: $line") }
      }.build()

      container.run()

      TimeUnit.SECONDS.sleep(1000)

      container.stop()
      container.remove()
   }

   @Test
   fun `Test Elasticsearch container`() {
      val container = ContainerFactory.kubernetesContainer {
         withName(ContainerName.of("elasticsearch-test"))
         withNamespace(Namespace.TEST)
         withImage(ImageURL.of("docker.elastic.co/elasticsearch/elasticsearch:8.11.3"))
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
         withExposedPort(PortMappingName.HTTP, NetworkPort.of(9200))
         withExposedPort(PortMappingName.TRANSPORT, NetworkPort.of(9300))
         withPortMapping(NetworkPort.of(9200), NetworkPort.of(30200))
         withPortMapping(NetworkPort.of(9300), NetworkPort.of(30300))
         withIsEphemeral(true)
         withLogLineCallback { line -> println("ELASTIC-OUTPUT: $line") }
      }.build()
      container.run()
      TimeUnit.SECONDS.sleep(120)
      container.stop()
      container.remove()
   }

   @ParameterizedTest
   @ValueSource(strings = ["DOCKER"/*, "KUBERNETES"*/])
   fun `Test image build`(containerType: String) {
      val imageName = ImageName.of("simple-alpine")
      val repository = RepositoryName.TEST

      DockerRegistryUtils.deleteImage("http://$REGISTRY", imageName.unwrap())

      val tempDir = Files.createTempDirectory("dockercontext-").toString()
      log.debug("Temp dir for docker-context created: {}", tempDir)
      val dockerfile = File(tempDir, "Dockerfile")
      val logTimeScript = File(tempDir, "log_time.sh")
      dockerfile.writeText(DOCKERFILE_CONTENT)
      logTimeScript.writeText(LOG_TIME_SCRIPT_CONTENT)

      log.debug("Dockerfile created: {}", dockerfile.absolutePath)
      log.debug("log_time.sh created: {}", logTimeScript.absolutePath)

      val imageBuilder = ContainerFactory.imageBuilder(ContainerType.valueOf(containerType))
         .withName(imageName)
         .withImageRegistry(RegistryURL.of(REGISTRY))
         .withInsecureRegistry(true)
         .withRepository(RepositoryName.TEST)
         .withNamespace(Namespace.TEST)
         .withDockerContextDir(Path.of(tempDir))
         .withLogLineCallback { line -> println("KANIKO-JOB-OUTPUT: ${Instant.now()} $line") }
         .withCustomProperty(ImageBuilder.PROP_LOCAL_KANIKO_DATA_PATH, "/home/thomas/kind/kaniko-data")

      val result = imageBuilder.buildImage()

      if (result) {
         val container = ContainerFactory.container {
            withType(ContainerType.valueOf(containerType))
            withName(ContainerName.of("simple-alpine-test"))
            withNamespace(Namespace.TEST)
            withImage(ImageURL.of("$REGISTRY/$repository/${imageName}:latest"))
            withIsEphemeral(true)
            withLogLineCallback { line -> println("SIMPLE-ALPINE-OUTPUT: $line") }
         }.build()

         container.run()

         TimeUnit.SECONDS.sleep(120)

         container.stop()
         container.remove()
      } else {
         log.error("Image build and push failed")
      }
   }

}