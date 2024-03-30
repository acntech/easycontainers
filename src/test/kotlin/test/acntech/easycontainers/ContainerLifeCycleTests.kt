package test.acntech.easycontainers

import no.acntech.easycontainers.docker.DockerConstants
import no.acntech.easycontainers.model.*
import org.apache.commons.lang3.time.DurationFormatUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.slf4j.LoggerFactory
import test.acntech.easycontainers.TestSupport.dockerHostAddress
import test.acntech.easycontainers.TestSupport.startContainer
import java.util.stream.Stream

class ContainerLifeCycleTests {

   companion object {

      private const val PARAM_GRACEFUL_SHUTDOWN = "graceful-shutdown"

      private val log = LoggerFactory.getLogger(ContainerLifeCycleTests::class.java)

      private val DOCKERFILE_CONTENT = """       
         # Use Alpine Linux as the base image
         FROM alpine:latest

         # Install dependencies
         # RUN apk add --no-cache curl netcat-openbsd openssh
         RUN apk add --no-cache openssh

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
            && echo '/usr/sbin/sshd &' >> /start.sh \
            && echo '/usr/local/bin/log-time.sh' >> /start.sh \
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
           sleep 1
         done
      """.trimIndent()

      init {
         System.setProperty(DockerConstants.PROP_DOCKER_HOST, "tcp://$dockerHostAddress:2375")
      }
   }

   class TestContainerProvider : ArgumentsProvider {
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
   @ArgumentsSource(TestContainerProvider::class)
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