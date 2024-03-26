package test.acntech.easycontainers

import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.docker.DockerConstants
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.net.NetworkUtils
import no.acntech.easycontainers.util.platform.PlatformUtils
import no.acntech.easycontainers.util.platform.PlatformUtils.convertLinuxPathToWindowsWslPath
import no.acntech.easycontainers.util.text.EMPTY_STRING
import org.apache.commons.lang3.time.DurationFormatUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

class ContainerTests {

   companion object {

      private const val PARAM_GRACEFUL_SHUTDOWN = "graceful-shutdown"

      private val log = LoggerFactory.getLogger(ContainerTests::class.java)

      // NOTE: Only valid for a Windows/WSL2 environment
      private val registryIpAddress = PlatformUtils.getWslIpAddress() ?: "localhost"

      private val nodeHostIpAddress = registryIpAddress

      private val dockerHostAddress = registryIpAddress

      private val registry = "${registryIpAddress}:5000"

      private val localKanikoPath = convertLinuxPathToWindowsWslPath("/home/thomas/kind/kaniko-data")

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

   @ParameterizedTest
   @ValueSource(
      strings = [
         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun `Test echo command execution`(containerType: String) {
      val platform = ContainerPlatformType.valueOf(containerType)
      val container = startContainer(platform, ExecutionMode.SERVICE, false)
      val runtime = container.getRuntime()

      val msg = "Hello, ${platform.name}!"

      val (exitCode, stdout, stderr) = container.execute(
         Executable.of("echo"),
         Args.of(msg),
         false,
         UnixDir.of("/"),
         null,
         10,
         TimeUnit.SECONDS
      )

      log.debug("Exit code: $exitCode")
      log.debug("Stdout: $stdout")
      log.debug("Stderr: $stderr")

      exitCode?.let {
         assertEquals(0, exitCode)
      }
      assertTrue(stdout.startsWith(msg))
      assertEquals(EMPTY_STRING, stderr)

      runtime.stop()
      assertEquals(ContainerState.STOPPED, runtime.getContainer().getState())
      runtime.delete()
      assertEquals(ContainerState.DELETED, runtime.getContainer().getState())
      log.debug("Container exit code: {}", container.getExitCode())
   }

   @ParameterizedTest
   @ValueSource(
      strings = [
//         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun `Test df command execution`(containerType: String) {
      val platform = ContainerPlatformType.valueOf(containerType)
      val container = startContainer(platform, ExecutionMode.SERVICE, false)
      val runtime = container.getRuntime()

      val (exitCode, stdout, stderr) = container.execute(
         Executable.of("df"),
         Args.of("-h"),
         false,
         UnixDir.of("/"),
         null,
         10,
         TimeUnit.SECONDS
      )

      log.debug("Exit code: $exitCode")
      log.debug("Stdout: $stdout")
      log.debug("Stderr: $stderr")

      exitCode?.let {
         assertEquals(0, exitCode)
      }

      // The first line of the output should contain Filesystem and Size
      assertTrue(stdout.contains("Filesystem"))
      assertTrue(stdout.contains("Size"))

      assertEquals(EMPTY_STRING, stderr)

      runtime.stop()
      assertEquals(ContainerState.STOPPED, runtime.getContainer().getState())
      runtime.delete()
      assertEquals(ContainerState.DELETED, runtime.getContainer().getState())
      log.debug("Container exit code: {}", container.getExitCode())
   }

   @ParameterizedTest
   @ValueSource(
      strings = [
//         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun `Test command execution with input`(containerType: String) {
      val platform = ContainerPlatformType.valueOf(containerType)
      val container = startContainer(platform, ExecutionMode.SERVICE, false)
      val runtime = container.getRuntime()

      val inputString = "Hello, ${platform.name}!"
      val inputStream = ByteArrayInputStream(inputString.toByteArray())

      val (exitCode, stdout, stderr) = container.execute(
         Executable.of("cat"),
         null,
         true,
         UnixDir.of("/"),
         inputStream,
         30,
         TimeUnit.SECONDS
      )

      log.debug("Exit code: $exitCode")
      log.debug("Stdout: $stdout")
      log.debug("Stderr: $stderr")

      exitCode?.let {
         assertEquals(0, it)
      }
      assertEquals(inputString, stdout)
      assertEquals(EMPTY_STRING, stderr)

      runtime.stop()
      assertEquals(ContainerState.STOPPED, runtime.getContainer().getState())
      runtime.delete()
      assertEquals(ContainerState.DELETED, runtime.getContainer().getState())
      log.debug("Container exit code: {}", container.getExitCode())
   }

   @ParameterizedTest
   @ValueSource(strings = ["DOCKER", "KUBERNETES"])
   fun `Test non-existent command execution`(containerType: String) {
      val platform = ContainerPlatformType.valueOf(containerType)
      val container = startContainer(platform, ExecutionMode.SERVICE, false)


      val runtime = container.getRuntime()

      val (exitCode, stdout, stderr) = container.execute(
         Executable.of("nonexistentcommand"),
         null,
         false,
         UnixDir.of("/"),
         null,
         10,
         TimeUnit.SECONDS
      )

      log.debug("Exit code: $exitCode")
      log.debug("Stdout: $stdout")
      log.debug("Stderr: $stderr")

      exitCode?.let {
         assertNotEquals(0, exitCode)
      }

      when (platform) {

         ContainerPlatformType.DOCKER -> {
            assertTrue(stdout.contains("nonexistentcommand"))
         }

         ContainerPlatformType.KUBERNETES -> {
            assertTrue(exitCode != 0)
            assertEquals(EMPTY_STRING, stdout)
//            assertTrue(stderr.contains("nonexistentcommand"))
         }
      }

      runtime.stop()
      assertEquals(ContainerState.STOPPED, runtime.getContainer().getState())
      runtime.delete()
      assertEquals(ContainerState.DELETED, runtime.getContainer().getState())
      log.debug("Container exit code: {}", container.getExitCode())
   }

   private fun startContainer(
      platform: ContainerPlatformType = ContainerPlatformType.DOCKER,
      executionMode: ExecutionMode = ExecutionMode.SERVICE,
      ephemeral: Boolean = false,
   ): Container {
      val imageName = "container-test"

      log.info("Testing the $imageName container with container type: $platform")

      val mappedLocalHttpPort = when (platform) {
         ContainerPlatformType.DOCKER -> 8080
         ContainerPlatformType.KUBERNETES -> 30080
      }

      val mappedLocalSshPort = when (platform) {
         ContainerPlatformType.DOCKER -> 8022
         ContainerPlatformType.KUBERNETES -> 30022
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
      assertTrue(container.getState() == ContainerState.INITIALIZING || container.getState() == ContainerState.RUNNING)

      container.waitForState(ContainerState.RUNNING, 10, TimeUnit.SECONDS)

      assertEquals(ContainerState.RUNNING, runtime.getContainer().getState())
      assertTrue(NetworkUtils.isPortOpen("localhost", mappedLocalHttpPort))
      assertTrue(NetworkUtils.isPortOpen("localhost", mappedLocalSshPort))

      TimeUnit.SECONDS.sleep(1)

      return container
   }


}