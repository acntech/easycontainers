package test.acntech.easycontainers

import no.acntech.easycontainers.model.Container
import no.acntech.easycontainers.model.ContainerPlatformType
import no.acntech.easycontainers.model.ContainerState
import no.acntech.easycontainers.model.ExecutionMode
import org.apache.commons.lang3.time.DurationFormatUtils
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
   fun startAndRunTestContainers() {
      val dockerContainer = startContainer(ContainerPlatformType.DOCKER, ExecutionMode.SERVICE)
      val k8sContainer = startContainer(ContainerPlatformType.KUBERNETES, ExecutionMode.SERVICE)
      TimeUnit.SECONDS.sleep(10 * 60)
   }

   class LifeCycleTestArgumentsProvider : ArgumentsProvider {
      override fun provideArguments(context: ExtensionContext?): Stream<out Arguments> {
         return Stream.of(
            Arguments.of("DOCKER", mapOf(PARAM_GRACEFUL_SHUTDOWN to "true")),
            Arguments.of("DOCKER", mapOf(PARAM_GRACEFUL_SHUTDOWN to "false")),
            Arguments.of("KUBERNETES", mapOf(PARAM_GRACEFUL_SHUTDOWN to "true")),
            Arguments.of("KUBERNETES", mapOf(PARAM_GRACEFUL_SHUTDOWN to "false"))
         )
      }
   }

   @ParameterizedTest
   @ArgumentsSource(LifeCycleTestArgumentsProvider::class)
   fun `Test start-stop-kill-and-delete the test-container`(containerType: String, params: Map<String, String>) {

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

      val platform = ContainerPlatformType.valueOf(containerType)

      val container = startContainer(platform, ExecutionMode.SERVICE)

      val stopGracefully = params["graceful-shutdown"]?.toBoolean() ?: true
      if (stopGracefully) {
         gracefulShutdown(container)
      } else {
         forcefulShutdown(container)
      }

      log.debug(
         "Container duration: {}",
         DurationFormatUtils.formatDuration(container.getDuration()!!.toMillis(), "mm'm:'ss's.'SSS'ms'", true)
      )
      assertNotNull(container.getDuration())

      log.debug("Container exit code: {}", container.getExitCode())
   }

}