package test.acntech.easycontainers

import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class ShowCaseTests {

   companion object {
      private val log = LoggerFactory.getLogger(ShowCaseTests::class.java)
   }

   @Test
   fun `Showcase Docker`() {
      val imageName = "container-test"

      val container = GenericContainer.builder().apply {
         withName(ContainerName.of("easycontainers-$imageName"))
         withImage(ImageURL.of("${TestSupport.registry}/test/$imageName:latest"))

         withContainerPlatformType(ContainerPlatformType.DOCKER)

         withEnv("LOG_TIME_MESSAGE", "Hello from Docker!!!")

         withOutputLineCallback { line -> println("DOCKER-CONTAINER-OUTPUT: $line") }

         // HTTP
         withExposedPort(PortMappingName.HTTP, NetworkPort.HTTP)
         withPortMapping(NetworkPort.HTTP, NetworkPort.of(8080))

         // SSH
         withExposedPort(PortMappingName.SSH, NetworkPort.SSH)
         withPortMapping(NetworkPort.SSH, NetworkPort.of(8022))

      }.build()

      val runtime = container.getRuntime()

      runtime.start()

      log.debug("Container state: ${container.getState()}")

      TimeUnit.SECONDS.sleep(5*60)

      container.getRuntime().delete()
   }

   @Test
   fun `Showcase Kubernetes`() {
      val imageName = "container-test"

      val container = GenericContainer.builder().apply {
         withNamespace("test")
         withName(ContainerName.of("easycontainers-$imageName"))
         withImage(ImageURL.of("${TestSupport.registry}/test/$imageName:latest"))

         withContainerPlatformType(ContainerPlatformType.KUBERNETES)

         withIsEphemeral(true)

         withEnv("LOG_TIME_MESSAGE", "Hello from k8s!!!")

         withOutputLineCallback { line -> println("KUBERNETES-CONTAINER-OUTPUT: $line") }

         // HTTP
         withExposedPort("http", 80)
         withPortMapping(80, 30080)

         // SSH
         withExposedPort("ssh", 22)
         withPortMapping(22, 30022)

      }.build()

      val runtime = container.getRuntime()

      runtime.start()

      log.debug("Container state: ${container.getState()}")

      TimeUnit.SECONDS.sleep(5*60)

      container.getRuntime().delete()
   }

}