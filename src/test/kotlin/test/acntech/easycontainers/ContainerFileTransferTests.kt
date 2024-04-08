package test.acntech.easycontainers

import no.acntech.easycontainers.model.ContainerPlatformType
import no.acntech.easycontainers.model.ExecutionMode
import no.acntech.easycontainers.model.UnixDir
import no.acntech.easycontainers.util.lang.guardedExecution
import no.acntech.easycontainers.util.text.FORWARD_SLASH
import no.acntech.easycontainers.util.text.NEW_LINE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory.getLogger
import test.acntech.easycontainers.TestSupport.startContainer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively

class ContainerFileTransferTests {

   companion object {
      private val log = getLogger(ContainerFileTransferTests::class.java)
   }

   @ParameterizedTest
   @ValueSource(
      strings = [
         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun testGetFile(containerType: String) {
      val platform = ContainerPlatformType.valueOf(containerType)
      val container = startContainer(platform, ExecutionMode.SERVICE, true)

      val path = container.getFile(UnixDir.of(FORWARD_SLASH), "log-time.sh")

      val content = Files.readString(path)
      log.debug("Content of file:$NEW_LINE$content")
      assertTrue(content.contains("while getopts"))
   }

   @ParameterizedTest
   @ValueSource(
      strings = [
         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun testPutAndGetFile(containerType: String) {
      val remoteDir = UnixDir.of("/tmp")
      val remoteFile = "remote_test.txt"

      val platform = ContainerPlatformType.valueOf(containerType)
      val container = startContainer(platform, ExecutionMode.SERVICE, true)

      val runtime = container.getRuntime()
      val content = "Hello '${container.getName()}' with runtime '${runtime.getName()}'"
      log.debug("Content of file: $content")

      // Create a new temp file in the temp directory with some content
      val tempFile = Files.createTempFile("test_", ".txt")
      try {
         Files.writeString(tempFile, content)

         // Call the target method
         container.putFile(tempFile, remoteDir, remoteFile)

         TimeUnit.SECONDS.sleep(3)

         val path = container.getFile(remoteDir, remoteFile)

         val receivedContent = Files.readString(path)
         log.debug("Content of received file: $receivedContent")

         assertEquals(content, receivedContent)
      } finally {
         tempFile.deleteIfExists()
      }
   }

   @OptIn(ExperimentalPathApi::class)
   @ParameterizedTest
   @ValueSource(
      strings = [
         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun testPutAndGetDirectory(containerType: String) {
      val remoteDir = UnixDir.of("/tmp")

      val platform = ContainerPlatformType.valueOf(containerType)
      val container = startContainer(platform, ExecutionMode.SERVICE, true)

      val runtime = container.getRuntime()
      log.debug("Container '${container.getName()}' with runtime '${runtime.getName()}'")

      val content1 = "Hello 1 '${container.getName()}' with runtime '${runtime.getName()}'"
      val content2 = "Hello 2 '${container.getName()}' with runtime '${runtime.getName()}'"

      // Create a new temp directory with a temp file in it
      val tempSendDir = Files.createTempDirectory("dir_test_send_")
      val tempReceiveDir = Files.createTempDirectory("dir_test_receive_")

      try {
         val rootDir = Files.createDirectories(tempSendDir.resolve("tar-root"))
         val file1 = File(rootDir.toFile(), "test_1.txt")
         val file2 = File(rootDir.toFile(), "test_2.txt")

         Files.writeString(file1.toPath(), content1)
         Files.writeString(file2.toPath(), content2)

         // Call the target method to put the directory
         container.putDirectory(rootDir, remoteDir)

         // Call the target method to get the directory
         val (path, files) = container.getDirectory(UnixDir.of(remoteDir.unwrap() + "/tar-root"), tempReceiveDir)

         log.debug("Received directory: $path")
         log.debug("Received files: $files")

         val tarRoot = tempReceiveDir.resolve("tar-root")

         // Check the received files
         val receivedFile1 = tarRoot.resolve(file1.name)
         val receivedFile2 = tarRoot.resolve(file2.name)

         // Various assertions
         assertEquals(2, files.size)
         assertEquals(2, Files.list(tarRoot).count())
         assertTrue(files.contains(receivedFile1))
         assertTrue(files.contains(receivedFile2))
         assertTrue(Files.exists(receivedFile1))
         assertTrue(Files.exists(receivedFile2))
         assertEquals(content1, Files.readString(receivedFile1))
         assertEquals(content2, Files.readString(receivedFile2))

      } finally {
         guardedExecution({ tempSendDir.deleteRecursively() })
         guardedExecution({ tempReceiveDir.deleteRecursively() })
      }
   }

}