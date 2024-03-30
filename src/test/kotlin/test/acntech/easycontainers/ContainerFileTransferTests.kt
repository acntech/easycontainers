package test.acntech.easycontainers

import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.io.toUtf8String
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory.getLogger
import test.acntech.easycontainers.TestSupport.startContainer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ContainerFileTransferTests {

   companion object {
      private val log = getLogger(ContainerFileTransferTests::class.java)
   }

   @ParameterizedTest
   @ValueSource(
      strings = [
//         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun testGetFile(containerType: String) {
      val platform = ContainerPlatformType.valueOf(containerType)
      val container = startContainer(platform, ExecutionMode.SERVICE, true)

      val runtime = container.getRuntime()

      val path = container.getFile(UnixDir.of("/"), "log-time.sh")

      val content = Files.readString(path)
      log.debug("Content of file: $content")
   }

   @ParameterizedTest
   @ValueSource(
      strings = [
//         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun testPutFile(containerType: String) {
      val platform = ContainerPlatformType.valueOf(containerType)
      val container = startContainer(platform, ExecutionMode.SERVICE, true)

      val runtime = container.getRuntime()
      val testContent = "Hello '${container.getName()}' with runtime '${runtime.getName()}'\n"

      // Create a new temp file in the temp directory with some content
      val tempFile = Files.createTempFile("test_", ".txt")
      Files.writeString(tempFile, testContent)

      // Call the target method
      val unixDir = UnixDir.of("/tmp")
      container.putFile(tempFile, unixDir, "remote_test.txt")

      TimeUnit.SECONDS.sleep(1)

      val readCommand = Executable.of("cat")
      val args = Args.of("/tmp/remote_test.txt")
      val stdOut = ByteArrayOutputStream()

      val (exitCode, stdErr) = container.execute(
         executable = readCommand,
         args = args,
         output = stdOut
      )

      assertNull(stdErr)
      assertEquals(0, exitCode)
      assertEquals(testContent, stdOut.toUtf8String())
   }

}