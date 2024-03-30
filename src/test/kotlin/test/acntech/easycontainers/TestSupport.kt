package test.acntech.easycontainers

import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.docker.DockerConstants
import no.acntech.easycontainers.kubernetes.K8sUtils
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.net.NetworkUtils
import no.acntech.easycontainers.util.platform.PlatformUtils
import org.junit.jupiter.api.Assertions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

object TestSupport {

   private val log: Logger = LoggerFactory.getLogger(TestSupport::class.java)

   // NOTE: Only valid for a Windows/WSL2 environment
   val registryIpAddress = PlatformUtils.getWslIpAddress() ?: "localhost"

   val nodeHostIpAddress = registryIpAddress

   val dockerHostAddress = registryIpAddress

   val registry = "${registryIpAddress}:5000"

   val localKanikoPath = PlatformUtils.convertLinuxPathToWindowsWslPath("/home/thomas/kind/kaniko-data")

   init {
      System.setProperty(DockerConstants.PROP_DOCKER_HOST, "tcp://$dockerHostAddress:2375")
   }

   fun startContainer(
      platform: ContainerPlatformType = ContainerPlatformType.DOCKER,
      executionMode: ExecutionMode = ExecutionMode.SERVICE,
      ephemeral: Boolean = false,
   ): Container {
      val imageName = "container-test"

      log.info("Testing the $imageName container with container type: $platform")

      val mappedLocalHttpPort = when (platform) {
         ContainerPlatformType.DOCKER -> 8080
         ContainerPlatformType.KUBERNETES -> if (K8sUtils.isRunningOutsideCluster()) 30080 else 80
      }

      val mappedLocalSshPort = when (platform) {
         ContainerPlatformType.DOCKER -> 8022
         ContainerPlatformType.KUBERNETES -> if (K8sUtils.isRunningOutsideCluster()) 30022 else 22
      }

      val container = GenericContainer.builder().apply {
         withName(ContainerName.of("$imageName-test"))
         withNamespace(Namespace.TEST)
         withImage(ImageURL.of("$registry/test/$imageName:latest"))

         withExecutionMode(executionMode)

         when (executionMode) {
            ExecutionMode.SERVICE -> {
               withEnv("LOG_TIME_SLEEP", "1")
               withEnv("LOG_TIME_EXIT_FLAG", "false")
            }

            ExecutionMode.TASK -> {
               withEnv("LOG_TIME_SLEEP", "1")
               withEnv("LOG_TIME_EXIT_FLAG", "true")
               withEnv("LOG_TIME_EXIT_CODE", "10")
            }
         }

         // HTTP
         withExposedPort(PortMappingName.HTTP, NetworkPort.HTTP)
         withPortMapping(NetworkPort.HTTP, NetworkPort.of(mappedLocalHttpPort))

         // SSH
         withExposedPort(PortMappingName.SSH, NetworkPort.SSH)
         withPortMapping(NetworkPort.SSH, NetworkPort.of(mappedLocalSshPort))

         withIsEphemeral(ephemeral)
         withOutputLineCallback { line -> println("${imageName.uppercase()}-CONTAINER-OUTPUT: $line") }
         withContainerPlatformType(platform)
      }.build()

      log.debug("Container created: $container")

      val runtime = container.getRuntime()
      log.debug("Container runtime: $runtime")

      runtime.start()
      Assertions.assertTrue(container.getState() == ContainerState.INITIALIZING || container.getState() == ContainerState.RUNNING)

      container.waitForState(ContainerState.RUNNING, 10, TimeUnit.SECONDS)

      Assertions.assertEquals(ContainerState.RUNNING, runtime.getContainer().getState())
      Assertions.assertTrue(NetworkUtils.isPortOpen("localhost", mappedLocalHttpPort))
      Assertions.assertTrue(NetworkUtils.isPortOpen("localhost", mappedLocalSshPort))

      TimeUnit.SECONDS.sleep(1)

      return container
   }

}