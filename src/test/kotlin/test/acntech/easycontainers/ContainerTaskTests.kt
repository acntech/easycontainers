package test.acntech.easycontainers

import no.acntech.easycontainers.model.ContainerPlatformType
import no.acntech.easycontainers.model.ContainerState
import no.acntech.easycontainers.model.ExecutionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class ContainerTaskTests {

   companion object {
      private val log = LoggerFactory.getLogger(ContainerTaskTests::class.java)
   }

   @ParameterizedTest
   @ValueSource(
      strings = [
         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun `Test task`(containerType: String) {
      log.info("Running test-task for container type: $containerType")
      val containerPlatformType = ContainerPlatformType.valueOf(containerType)

      val container = TestSupport.startContainer(containerPlatformType, ExecutionMode.TASK, true)

      val completed = container.waitForCompletion(10, TimeUnit.SECONDS)

      assertTrue(completed, "Task did not complete within the time limit")

      val exitCode = container.getExitCode()

      log.debug("Container state: ${container.getState()}")
      log.debug("Container exit code: $exitCode")

      assertEquals(10, exitCode)
      assertTrue(container.getState() == ContainerState.STOPPED || container.getState() == ContainerState.DELETED)

      container.getRuntime().delete()
   }

}