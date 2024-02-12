package test.acntech.easycontainers

import no.acntech.easycontainers.ContainerFactory
import no.acntech.easycontainers.ContainerType
import no.acntech.easycontainers.ImageBuilder
import no.acntech.easycontainers.docker.DockerConstants
import no.acntech.easycontainers.docker.DockerRegistryUtils
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.net.NetworkUtils
import no.acntech.easycontainers.util.platform.PlatformUtils
import no.acntech.easycontainers.util.platform.PlatformUtils.getWslPath
import org.awaitility.Awaitility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class ContainerTests {

   companion object {

      private val log = LoggerFactory.getLogger(ContainerTests::class.java)

      // NOTE: Only valid for a Windows/WSL2 environment
      private val registryIpAddress = PlatformUtils.getWslIpAddress() ?: "localhost"

      private val nodeHostIpAddress = registryIpAddress

      private val dockerHostAddress = registryIpAddress

      private val registry = "${registryIpAddress}:5000"

      private val localKanikoPath = getWslPath("/home/thomas/kind/kaniko-data")

      private val DOCKERFILE_CONTENT = """       
         # Use Alpine Linux as the base image
         FROM alpine:latest

         # Install dependencies
         RUN apk add --no-cache curl netcat-openbsd openssh

         # Additional setup SSH
         RUN sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config \
             && sed -i 's/#PasswordAuthentication yes/PasswordAuthentication yes/' /etc/ssh/sshd_config \
             && echo "root:root" | chpasswd \
             && ssh-keygen -A
         
         # Copy the log-time.sh script to the container
         COPY log-time.sh /usr/local/bin/log-time.sh
         
         # Make the log-time.sh script executable
         RUN chmod +x /usr/local/bin/log-time.sh
         
         # Create a startup script directly in the Dockerfile
         RUN echo '#!/bin/sh' > /start.sh \
             && echo '/usr/local/bin/log-time.sh &' >> /start.sh \
             && echo '/usr/sbin/sshd -D' >> /start.sh \
             && chmod +x /start.sh
         
         # Expose necessary ports (the SSH port)
         EXPOSE 22
         
         # Define the container's default behavior
         CMD ["/start.sh"]

    """.trimIndent()

      private val LOG_TIME_SCRIPT_CONTENT = """
         count=1
         while true
         do
           echo "${'$'}{count}: ${'$'}(date)"
           count=${'$'}((count+1))
           sleep 2
         done
      """.trimIndent()

      init {
         System.setProperty(DockerConstants.PROP_DOCKER_HOST, "tcp://$dockerHostAddress:2375")
      }
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
         withImage(ImageURL.of("$registry/test/$imageName:latest"))
         withExposedPort(PortMappingName.HTTP, NetworkPort.HTTP)

         // This will work for both Docker and Kubernetes since
         // NodePort is used for Kubernetes when running this test outside the cluster
         withPortMapping(NetworkPort.HTTP, NetworkPort.of(30080))

         withIsEphemeral(true)
         withOutputLineCallback { line -> println("HTTPD-OUTPUT: $line") }
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
         withImage(ImageURL.of("$registry/test/alpine-test:latest"))
         withIsEphemeral(true)
         withOutputLineCallback { line -> println("SIMPLE-ALPINE-OUTPUT: $line") }
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
         withOutputLineCallback { line -> println("ELASTIC-OUTPUT: $line") }
      }.build()
      container.run()
      TimeUnit.SECONDS.sleep(120)
      container.stop()
      container.remove()
   }

   @ParameterizedTest
   @CsvSource(
         "DOCKER, 30021",
         "KUBERNETES, 30022"
   )
   fun `Test alpine SSH image build and run`(containerType: String, sshPort: Int) {
      log.info("Testing Alpine SSH container with container type: {}", containerType)

      val imageName = ImageName.of("alpine-test-ssh")
      val repository = RepositoryName.TEST

      DockerRegistryUtils.deleteImage("http://$registry", imageName.unwrap())

      val tempDir = Files.createTempDirectory("dockercontext-").toString()
      log.debug("Temp dir for docker-context created: {}", tempDir)
      val dockerfile = File(tempDir, "Dockerfile")
      val logTimeScript = File(tempDir, "log-time.sh")
      dockerfile.writeText(DOCKERFILE_CONTENT)
      logTimeScript.writeText(LOG_TIME_SCRIPT_CONTENT)

      log.debug("Dockerfile created: {}", dockerfile.absolutePath)
      log.debug("log-time.sh created: {}", logTimeScript.absolutePath)

      val imageBuilder = ContainerFactory.imageBuilder(ContainerType.valueOf(containerType))
         .withName(imageName)
         .withVerbosity(Verbosity.DEBUG)
         .withImageRegistry(RegistryURL.of(registry))
         .withInsecureRegistry(true)
         .withRepository(RepositoryName.TEST)
         .withNamespace(Namespace.TEST)
         .withDockerContextDir(Path.of(tempDir))
         .withOutputLineCallback { line -> println("JOB-OUTPUT: ${Instant.now()} $line") } // Will only work for Kubernetes
         .withCustomProperty(ImageBuilder.PROP_LOCAL_KANIKO_DATA_PATH, localKanikoPath)

      val result = imageBuilder.buildImage()
      assertTrue(result, "Image build failed")

      val container = ContainerFactory.container {
         withType(ContainerType.valueOf(containerType))
         withName(ContainerName.of("alpine-ssh-test"))
         withNamespace(Namespace.TEST)
         withImage(ImageURL.of("$registry/$repository/${imageName}:latest"))
         withExposedPort(PortMappingName.SSH, NetworkPort.SSH)
         withPortMapping(NetworkPort.SSH, NetworkPort.of(sshPort))
         withIsEphemeral(true)
         withMaxLifeTime(Duration.of(10 * 60, ChronoUnit.SECONDS))
         withOutputLineCallback { line -> println("ALPINE-SSH-OUTPUT: $line") }
      }.build()

      // Run the container
      container.run()

      container.getState().let {
         assertTrue(
            it == Container.State.RUNNING || it == Container.State.STARTED,
            "Container is not started or running"
         )
      }

      Awaitility.await().atMost(10 * 60, TimeUnit.SECONDS).until {
         NetworkUtils.isPortOpen(nodeHostIpAddress, sshPort) ||
            (container.getState() != Container.State.STARTED && container.getState() != Container.State.RUNNING)
      }

      // Check that the SSH port is open
      assertTrue(NetworkUtils.isPortOpen(nodeHostIpAddress, sshPort), "$nodeHostIpAddress:$sshPort is not open")

      // Stop and check state
      container.stop()
      assertEquals(Container.State.STOPPED, container.getState(), "Container is not stopped")

      // Remove and check state
      container.remove()
      assertEquals(Container.State.REMOVED, container.getState(), "Container is not removed")

   }

}