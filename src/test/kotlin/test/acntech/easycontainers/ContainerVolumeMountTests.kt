package test.acntech.easycontainers

import no.acntech.easycontainers.ContainerBuilderCallback
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.platform.PlatformUtils
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

class ContainerVolumeMountTests {

   companion object {
      private val log = LoggerFactory.getLogger(ContainerVolumeMountTests::class.java)
   }

   class ContainerVolumeMountTestsProvider : ArgumentsProvider {
      override fun provideArguments(context: ExtensionContext?): Stream<out Arguments> {
         return Stream.of(
            Arguments.of(ContainerPlatformType.DOCKER, "/tmp"),
//            Arguments.of(ContainerPlatformType.KUBERNETES, "/home/thomas/kind/share"),
         )
      }
   }

   @ParameterizedTest
   @ArgumentsSource(ContainerVolumeMountTestsProvider::class)
   fun `Test mounting simple host volume `(containerType: ContainerPlatformType, hostPath: String) {

      val name = VolumeName.of("test-volume")
      val subDir = "test-mount"
      val mountDir = UnixDir.of("/mnt/$subDir")
      val hostDir = Path.of("$hostPath/$subDir")

      createTestFilesInSharedDir(hostDir)

      val callback: ContainerBuilderCallback = object : ContainerBuilderCallback {

         override fun configure(builder: ContainerBuilder<*>) {
            builder.withVolume(Volume(name, mountDir, hostDir))
         }

      }

      val container = TestSupport.startContainer(
         containerType,
         ExecutionMode.SERVICE,
         true,
         callback
      )

      log.debug("Container state: ${container.getState()}")

      TimeUnit.SECONDS.sleep(10 * 60)

      container.getRuntime().stop()
      container.getRuntime().delete()
   }

   private fun createTestFilesInSharedDir(hostDir: Path) {
      val actualDir = Path.of(
         if (PlatformUtils.isWslInstalled()) {
            PlatformUtils.convertUnixPathToWindowsWslPath(hostDir.toString())
         } else {
            hostDir.toString()
         }
      )

      Files.createDirectories(actualDir)

      val file1 = actualDir.resolve("hello-1.txt")
      file1.toFile().createNewFile()

      val file2 = actualDir.resolve("hello-2.txt")
      file2.toFile().createNewFile()

      file1.toFile().writeText("Hello, world 1!")
      file2.toFile().writeText("Hello, world 2!")

      log.debug("Created test files ${file1.toAbsolutePath()} and ${file2.toAbsolutePath()} in shared host directory: $actualDir")
   }

}