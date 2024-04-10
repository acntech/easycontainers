package test.acntech.easycontainers

import no.acntech.easycontainers.model.Container
import no.acntech.easycontainers.model.ContainerPlatformType
import no.acntech.easycontainers.model.ContainerState
import no.acntech.easycontainers.model.ExecutionMode
import org.apache.commons.lang3.time.DurationFormatUtils
import org.bouncycastle.crypto.util.JournaledAlgorithm.getState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.slf4j.LoggerFactory
import test.acntech.easycontainers.TestSupport.startContainer
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

class ContainerLifeCycleTests {

   companion object {
      private val log = LoggerFactory.getLogger(ContainerLifeCycleTests::class.java)
      private const val PARAM_GRACEFUL_SHUTDOWN = "graceful-shutdown"
   }


   @Test
   fun startAndRunServiceTestContainers() {
//      val dockerContainer = startContainer(ContainerPlatformType.DOCKER, ExecutionMode.SERVICE)
      val k8sContainer = startContainer(ContainerPlatformType.KUBERNETES, ExecutionMode.SERVICE)
      TimeUnit.SECONDS.sleep(10 * 60)
   }

   @Test
   fun startAndRunTaskTestContainers() {
      try {
         val k8sContainer = startContainer(ContainerPlatformType.KUBERNETES, ExecutionMode.TASK)
         //      val dockerContainer = startContainer(ContainerPlatformType.DOCKER, ExecutionMode.TASK)
      } catch (e: Exception) {
         log.error("Error starting container", e)
      }

      TimeUnit.SECONDS.sleep(10 * 60)
   }

   class LifeCycleTestArgumentsProvider : ArgumentsProvider {
      override fun provideArguments(context: ExtensionContext?): Stream<out Arguments> {
         return Stream.of(
            Arguments.of(ContainerPlatformType.DOCKER, ExecutionMode.SERVICE, mapOf(PARAM_GRACEFUL_SHUTDOWN to "true")),
            Arguments.of(ContainerPlatformType.DOCKER, ExecutionMode.SERVICE, mapOf(PARAM_GRACEFUL_SHUTDOWN to "false")),
            Arguments.of(ContainerPlatformType.KUBERNETES, ExecutionMode.SERVICE, mapOf(PARAM_GRACEFUL_SHUTDOWN to "true")),
            Arguments.of(ContainerPlatformType.KUBERNETES, ExecutionMode.SERVICE, mapOf(PARAM_GRACEFUL_SHUTDOWN to "false"))
         )
      }
   }

   @ParameterizedTest
   @ArgumentsSource(LifeCycleTestArgumentsProvider::class)
   fun `Test start-stop-kill-and-delete the test-container`(
      containerPlatformType: ContainerPlatformType,
      executionMode: ExecutionMode,
      params: Map<String, String>,
   ) {

      fun gracefulShutdown(container: Container) {
         log.debug("Gracefully shutting down the container: ${container.getName()}")
         val runtime = container.getRuntime()
         runtime.stop()
         assertEquals(ContainerState.STOPPED, runtime.getContainer().getState())
         runtime.delete()
         assertEquals(ContainerState.DELETED, runtime.getContainer().getState())
      }

      fun forcefulShutdown(container: Container) {
         log.debug("Forcefully shutting down the container: ${container.getName()}")
         val runtime = container.getRuntime()
         runtime.kill()
         assertEquals(ContainerState.STOPPED, runtime.getContainer().getState())
         runtime.delete(true)
         assertEquals(ContainerState.DELETED, runtime.getContainer().getState())
      }

      val container = startContainer(containerPlatformType, executionMode)

      TimeUnit.SECONDS.sleep(2)

      val stopGracefully = params[PARAM_GRACEFUL_SHUTDOWN]?.toBoolean() ?: true
      if (stopGracefully) {
         gracefulShutdown(container)
      } else {
         forcefulShutdown(container)
      }

      assertEquals(ContainerState.DELETED, container.getState())
      assertNotNull(container.getDuration())

      log.debug(
         "Container duration: {}",
         DurationFormatUtils.formatDuration(
            container.getDuration()!!.toMillis(),
            "mm'm:'ss's.'SSS'ms'",
            true)
      )

      log.debug("Container exit code: ${container.getExitCode()}")
   }

}