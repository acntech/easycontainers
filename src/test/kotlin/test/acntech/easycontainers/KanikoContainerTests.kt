package test.acntech.easycontainers

import no.acntech.easycontainers.Environment
import no.acntech.easycontainers.custom.KanikoContainer
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.text.truncate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.slf4j.LoggerFactory
import test.acntech.easycontainers.TestSupport.getActualDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

class KanikoContainerTests {

   companion object {
      private val log = LoggerFactory.getLogger(KanikoContainerTests::class.java)
   }

   class KanikoContainerTestsProvider : ArgumentsProvider {
      override fun provideArguments(context: ExtensionContext?): Stream<out Arguments> {
         return Stream.of(
            Arguments.of(ContainerPlatformType.DOCKER, "/tmp"), // in Docker, any directory can be shared
            Arguments.of(ContainerPlatformType.KUBERNETES, Environment.k8sKanikoDataHostDir),
         )
      }
   }

   @ParameterizedTest
   @ArgumentsSource(KanikoContainerTestsProvider::class)
   fun `Test Kaniko container`(containerType: ContainerPlatformType, hostPath: String) {

      val volumeNameVal = Environment.k8sKanikoDataPvcName.substringBefore("-pvc").also {
         log.debug("Using the K8s Kaniko PVC name (less the '-pvc' suffix) as volume name: $it")
      }

      val volumeName = VolumeName.of(volumeNameVal)

      val dockerContextSubDir = UUID.randomUUID().toString().truncate(5)
      val hostDir = Path.of(hostPath).also {
         log.debug("Using host directory as basis for Kaniko-data mount: $it")
      }

      val volume = Volume(
         volumeName,
         UnixDir.of(KanikoContainer.KanikoContainerBuilder.KANIKO_DATA_VOLUME_MOUNT_PATH),
         hostDir
      )

      createDockerFiles(hostDir.resolve(dockerContextSubDir))

      val container = KanikoContainer.KanikoContainerBuilder().apply {
         withContainerPlatformType(containerType)
         withNamespace(Namespace.TEST)
         withRegistry(Environment.defaultRegistryEndpoint)
         withRepository(RepositoryName.TEST)
         withInsecureRegistry(true)
         withDockerContextVolume(volume)
         withDockerContextSubDir("/$dockerContextSubDir")
         withOutputLineCallback { line -> log.debug("$containerType-Kaniko-container-output: $line") }
      }.build()

      val runtime = container.getRuntime()
      runtime.start()

      container.waitForCompletion(30, TimeUnit.SECONDS)

      val exitCode = container.getExitCode()
      log.debug("Container state: ${container.getState()}")
      log.debug("Container exit code: $exitCode")

      assertEquals(0, exitCode)
   }

   private fun createDockerFiles(hostDir: Path) {
      val dockerContextDir = getActualDir(hostDir)

      val helloFile = dockerContextDir.resolve("hello.txt")
      val helloFileContent = "Hello from Alpine!!!"

      val dockerfile = dockerContextDir.resolve("Dockerfile")
      val dockerfileContent = """
         FROM alpine:latest
         COPY hello.txt /hello.txt
         CMD ["cat", "/hello.txt"]                  
      """.trimIndent()

      Files.createDirectories(dockerContextDir)

      helloFile.toFile().writeText(helloFileContent)
      dockerfile.toFile().writeText(dockerfileContent)

      log.debug(
         "Created Docker files '${dockerfile.toAbsolutePath()}'" +
            " and '${helloFile.toAbsolutePath()}' in host directory: $dockerContextDir"
      )
   }


}