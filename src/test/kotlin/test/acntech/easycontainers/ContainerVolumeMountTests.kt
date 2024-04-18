package test.acntech.easycontainers

import no.acntech.easycontainers.ContainerBuilderCallback
import no.acntech.easycontainers.Environment
import no.acntech.easycontainers.Environment.k8sGeneralDataPvcName
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.platform.PlatformUtils
import no.acntech.easycontainers.util.text.FORWARD_SLASH
import no.acntech.easycontainers.util.text.truncate
import org.apache.commons.io.FileUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.stream.Stream
import kotlin.io.path.readText

class ContainerVolumeMountTests {

   companion object {
      private val log = LoggerFactory.getLogger(ContainerVolumeMountTests::class.java)
   }

   class ContainerVolumeMountTestsProvider : ArgumentsProvider {
      override fun provideArguments(context: ExtensionContext?): Stream<out Arguments> {
         return Stream.of(
//            Arguments.of(ContainerPlatformType.DOCKER, "/tmp"), // in Docker, any directory can be shared
            Arguments.of(ContainerPlatformType.KUBERNETES, Environment.k8sGeneralDataHostDir),
         )
      }
   }

   @ParameterizedTest
   @ArgumentsSource(ContainerVolumeMountTestsProvider::class)
   fun `Test mounting simple host volume `(containerType: ContainerPlatformType, hostPath: String) {

      val volumeNameVal = k8sGeneralDataPvcName.substringBefore("-pvc").also {
         log.debug("Using the k8s PVC name (less the '-pvc' suffix) as volume name: $it")
      }

      val name = VolumeName.of(volumeNameVal)

      val subDir = UUID.randomUUID().toString().truncate(8)
      val mountDir = UnixDir.of("/mnt/test")
      val hostDir = Path.of(hostPath).also {
         log.debug("Using host directory as a basis for mount: $it")
      }

      createTestFiles(hostDir.resolve(subDir))

      val callback: ContainerBuilderCallback = object : ContainerBuilderCallback {

         override fun configure(builder: ContainerBuilder<*>) {
            val volume = Volume(name, mountDir, hostDir)
            builder.withVolume(volume)
         }

      }

      val container = TestSupport.startContainer(
         containerType,
         ExecutionMode.SERVICE,
         true,
         callback
      )

      log.debug("Container state: ${container.getState()}")

      val targetTestDir = mountDir.value + FORWARD_SLASH + subDir

      val localFile1 = container.getFile(targetTestDir, "hello-1.txt")
      val localFile2 = container.getFile(targetTestDir, "hello-2.txt")

      val content1 = localFile1.readText().also {
         log.debug("Content of file 1: $it")
      }
      val content2 = localFile2.readText().also {
         log.debug("Content of file 2: $it")
      }

      assertEquals("Hello, world 1!", content1)
      assertEquals("Hello, world 2!", content2)

      container.getRuntime().stop()
      container.getRuntime().delete(true)
      deleteTestFiles(hostDir.resolve(subDir))
   }

   private fun createTestFiles(hostDir: Path) {
      val actualDir = getActualDir(hostDir)

      Files.createDirectories(actualDir)

      val file1 = actualDir.resolve("hello-1.txt")
      file1.toFile().createNewFile()

      val file2 = actualDir.resolve("hello-2.txt")
      file2.toFile().createNewFile()

      file1.toFile().writeText("Hello, world 1!")
      file2.toFile().writeText("Hello, world 2!")

      log.debug(
         "Created test files ${file1.toAbsolutePath()} and ${file2.toAbsolutePath()}" +
            " in the shared host directory: $actualDir"
      )
   }

   private fun deleteTestFiles(hostDir: Path) {
      val actualDir = getActualDir(hostDir)
      FileUtils.deleteDirectory(actualDir.toFile())
      log.debug("Deleted test files in dir: $actualDir")
   }

   private fun getActualDir(hostDir: Path): Path {
      return Path.of(
         if (PlatformUtils.isWslInstalled()) {
            PlatformUtils.convertUnixPathToWindowsWslPath(hostDir.toString())
         } else {
            hostDir.toString()
         }
      )
   }

}