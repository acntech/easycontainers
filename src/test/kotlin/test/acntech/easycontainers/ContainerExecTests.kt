package test.acntech.easycontainers

import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.io.toUtf8String
import no.acntech.easycontainers.util.text.FORWARD_SLASH
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import test.acntech.easycontainers.TestSupport.shutdownContainer
import test.acntech.easycontainers.TestSupport.startContainer
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ContainerExecTests {

   companion object {
      private val log = LoggerFactory.getLogger(ContainerExecTests::class.java)
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
      val output = ByteArrayOutputStream()

      val msg = "Hello, ${platform.name}!"

      val (exitCode, stderr) = container.execute(
         Executable.of("echo"),
         Args.of(msg),
         false,
         UnixDir.of(FORWARD_SLASH),
         null,
         output,
         10,
         TimeUnit.SECONDS
      )

      log.debug("Exit code: $exitCode")
      log.debug("Stdout: ${output.toUtf8String()}")
      log.debug("Stderr: $stderr")

      exitCode?.let {
         assertEquals(0, exitCode)
      }
      assertTrue(output.toUtf8String().startsWith(msg))
      assertNull(stderr)

      shutdownContainer(container)
      log.debug("Container exit code: {}", container.getExitCode())
   }

   @ParameterizedTest
   @ValueSource(
      strings = [
         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun `Test df command execution`(containerType: String) {
      val platform = ContainerPlatformType.valueOf(containerType)
      val container = startContainer(platform, ExecutionMode.SERVICE, false)
      val runtime = container.getRuntime()
      val output = ByteArrayOutputStream()

      val (exitCode, stderr) = container.execute(
         Executable.of("df"),
         Args.of("-h"),
         false,
         UnixDir.of(FORWARD_SLASH),
         null,
         output,
         10,
         TimeUnit.SECONDS
      )

      val stdout = output.toUtf8String()

      log.debug("Exit code: $exitCode")
      log.debug("Stdout: $stdout")
      log.debug("Stderr: $stderr")

      exitCode?.let {
         assertEquals(0, exitCode)
      }

      assertNull(stderr)
      // The first line of the output should contain Filesystem and Size
      assertTrue(stdout.contains("Filesystem"))
      assertTrue(stdout.contains("Size"))

      shutdownContainer(container)
      log.debug("Container exit code: {}", container.getExitCode())
   }

   @ParameterizedTest
   @ValueSource(
      strings = [
//         "DOCKER", // Fails with "Does not support hijacking" error - unresolved
         "KUBERNETES"
      ]
   )
   fun `Test 'cat' command with input and output`(containerType: String) {
      val platform = ContainerPlatformType.valueOf(containerType)
      val container = startContainer(platform, ExecutionMode.SERVICE, true)
      val runtime = container.getRuntime()
      val inputString = "Hello, ${platform.name}!"
      val input = inputString.byteInputStream()
      val output = ByteArrayOutputStream() // Output stream to capture the output - should be the same as the input

      val (exitCode, stderr) = container.execute(
         Executable.of("cat"),
         null,
         true,
         UnixDir.of(FORWARD_SLASH),
         input,
         output,
         20,
         TimeUnit.SECONDS
      )

      val stdout = output.toUtf8String()

      log.debug("Exit code: $exitCode")
      log.debug("Stdout: $stdout")
      log.debug("Stderr: $stderr")

      exitCode?.let {
         assertEquals(0, it)
      }
      assertEquals(inputString, stdout)
      assertNull(stderr)

      shutdownContainer(container)
      log.debug("Container exit code: {}", container.getExitCode())
   }

   @ParameterizedTest
   @ValueSource(
      strings = [
         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun `Test non-existent command execution`(value: String) {
      val platform = ContainerPlatformType.valueOf(value)
      val container = startContainer(platform, ExecutionMode.SERVICE, false)
      val output = ByteArrayOutputStream()
      val runtime = container.getRuntime()

      when (platform) {
         ContainerPlatformType.DOCKER -> {
            val (exitCode, stderr) = container.execute(
               Executable.of("nonexistentcommand"),
               null,
               false,
               UnixDir.of(FORWARD_SLASH),
               null,
               output,
               10,
               TimeUnit.SECONDS
            )

            val stdout = output.toUtf8String()

            log.debug("Exit code: $exitCode")
            log.debug("Stdout: $stdout")
            log.debug("Stderr: $stderr")

            exitCode?.let {
               assertNotEquals(0, exitCode)
            }

            assertTrue(stdout.contains("nonexistentcommand"))
         }

         ContainerPlatformType.KUBERNETES -> {
            assertThrows<Exception> {
               container.execute(
                  Executable.of("nonexistentcommand"),
                  null,
                  false,
                  UnixDir.of(FORWARD_SLASH),
                  null,
                  output,
                  10,
                  TimeUnit.SECONDS
               )
            }
         }
      }

      shutdownContainer(container)
   }

}