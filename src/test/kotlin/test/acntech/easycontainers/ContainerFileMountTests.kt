package test.acntech.easycontainers

import no.acntech.easycontainers.ContainerBuilderCallback
import no.acntech.easycontainers.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.io.path.readText

class ContainerFileMountTests {

   companion object {
      private val log = LoggerFactory.getLogger(ContainerFileMountTests::class.java)
   }


   @ParameterizedTest
   @ValueSource(
      strings = [
         "DOCKER",
         "KUBERNETES"
      ]
   )
   fun `Test mounting container file (single file mount)`(containerType: String) {
      log.info("Testing mount container file on platform: $containerType")

      val containerPlatformType = ContainerPlatformType.valueOf(containerType)

      val content = "Hello, world!"
      val mount = UnixDir.of("/tmp/test1")
      val fileName = "hello.txt"

      val callback: ContainerBuilderCallback = object : ContainerBuilderCallback {

         override fun configure(builder: ContainerBuilder<*>) {
            builder.withContainerFile(
               ContainerFile(
                  ContainerFileName.of(fileName),
                  mount,
                  content,
                  Files.createTempFile("hello", ".txt")
               )
            )
         }

      }

      val container = TestSupport.startContainer(
         containerPlatformType,
         ExecutionMode.SERVICE,
         true,
         callback
      )

      log.debug("Container state: ${container.getState()}")

      val file = container.getFile(mount, fileName)
      val readContent = file.readText()
      assertEquals(content, readContent)

      container.getRuntime().stop()
      container.getRuntime().delete(true)
   }

}