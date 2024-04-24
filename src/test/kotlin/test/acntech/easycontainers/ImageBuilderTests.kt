package test.acntech.easycontainers

import no.acntech.easycontainers.Environment.defaultRegistryEndpoint
import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.ImageBuilder
import no.acntech.easycontainers.docker.DockerRegistryUtils
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.net.NetworkUtils
import no.acntech.easycontainers.util.text.NEW_LINE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.slf4j.LoggerFactory
import test.acntech.easycontainers.TestSupport.dockerFileContent
import test.acntech.easycontainers.TestSupport.scriptContent
import test.acntech.easycontainers.TestSupport.shutdownContainer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

class ImageBuilderTests {

   companion object {
      private val log = LoggerFactory.getLogger(ImageBuilderTests::class.java)
   }

   @ParameterizedTest
   @CsvSource(
//      "DOCKER, 8022",
      "KUBERNETES, 30022"
   )
   fun `Test build and run image`(containerType: String, sshPort: Int) {
      log.info("Testing Alpine SSH test-job container with container type: $containerType")

      val platformType = ContainerPlatformType.valueOf(containerType.uppercase())

      val imageName = ImageName.of("easycontainers-junit-test-job")
      val repository = RepositoryName.TEST
      val imageUrlVal = "$defaultRegistryEndpoint/$repository/$imageName"
      log.info("Image URL: $imageUrlVal")

      DockerRegistryUtils.deleteImage("http://$defaultRegistryEndpoint/$repository", imageName.unwrap())

      val dockerContextDir = Files.createTempDirectory("temp-docker-context-").toString().also {
         log.debug("Docker context (temp) dir created: $it")
      }

      val dockerfile = File(dockerContextDir, "Dockerfile")
      val logTimeScript = File(dockerContextDir, "log-time.sh")

      dockerfile.writeText(dockerFileContent)
      logTimeScript.writeText(
         scriptContent
            .replace("#MESSAGE#", "Hello from $platformType container")
            .replace("#COUNT#", "10")
      )

      log.debug("Dockerfile created in: ${dockerfile.absolutePath}")
      log.debug("Dockerfile contents:$NEW_LINE$$NEW_LINE${dockerfile.readText()}$NEW_LINE$NEW_LINE")

      log.debug("log-time.sh created in : ${logTimeScript.absolutePath}")
      log.debug("log-time.sh contents:$NEW_LINE$$NEW_LINE{logTimeScript.readText()}")

      val imageBuilder = ImageBuilder.of(platformType)
         .withName(imageName)
         .withVerbosity(Verbosity.DEBUG)
         .withImageRegistry(RegistryURL.of(defaultRegistryEndpoint.substringAfter("://")))
         .withInsecureRegistry(true)
         .withRepository(RepositoryName.TEST)
         .withNamespace(Namespace.TEST)
         .withDockerContextDir(Path.of(dockerContextDir))

         // Will only work for Kubernetes!!!
         .withOutputLineCallback { line -> println("KUBE-KANIKO-JOB-OUTPUT: ${Instant.now()} - $line") }

      val result = imageBuilder.buildImage()

      assertTrue(result, "Image build failed")

      log.info("Image built successfully: $imageUrlVal")

      TimeUnit.SECONDS.sleep(2)

      // Now we have a new version of the image in the registry, lets run it in a container

      // Build it...
      val container = GenericContainer.builder().apply {
         withContainerPlatformType(platformType)
         withExecutionMode(ExecutionMode.TASK)
         withIsEphemeral(false)
         withName(ContainerName.of("easycontainers-junit-test-job"))
         withNamespace(Namespace.TEST)
         withImage(ImageURL.of(imageUrlVal))
         withExposedPort("ssh", 22)
         withPortMapping(22, sshPort)
         withIsEphemeral(true)
         withMaxLifeTime(30, TimeUnit.SECONDS)
         withOutputLineCallback { line -> println("$platformType-OUTPUT: $line") }
      }.build()

      // Run it...
      container.getRuntime().start()
      val running = container.waitForState(Container.State.RUNNING, 60, TimeUnit.SECONDS)
      assertTrue(running, "Container did not start within 60 seconds")
      assertTrue(NetworkUtils.isTcpPortOpen("localhost", sshPort))

      // Wait for it...
      val completed = container.waitForCompletion(60, TimeUnit.SECONDS)

      assertTrue(completed, "Container did not complete within 10 seconds")

      // Check it...
      val exitVal = container.getExitCode()
      assertEquals(10, exitVal, "Container exited with code $exitVal, expected 10")

      // Delete it...

      shutdownContainer(container)
   }


}