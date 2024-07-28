package test.acntech.easycontainers.k8s

import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.ExecWatch
import no.acntech.easycontainers.kubernetes.K8sRuntime
import no.acntech.easycontainers.model.ContainerPlatformType
import no.acntech.easycontainers.model.ExecutionMode
import no.acntech.easycontainers.util.text.NEW_LINE
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import test.acntech.easycontainers.TestSupport.startContainer
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit


class NativeK8sTests {

   companion object {
      private val log: Logger = LoggerFactory.getLogger(NativeK8sTests::class.java)
   }

   private var client = KubernetesClientBuilder().build()

   @BeforeEach
   fun setUp() {
      log.info("Setting up")
      client = KubernetesClientBuilder().build()
   }

   @AfterEach
   fun tearDown() {
      log.info("Tearing down")
      client.close()
   }

   @Test
   fun `test native exec`() {
      val container = startContainer(
         platform = ContainerPlatformType.KUBERNETES,
         executionMode = ExecutionMode.SERVICE,
         ephemeral = true,
      )

      val runtime = container.getRuntime() as K8sRuntime

      log.debug("Container started:$NEW_LINE$container")

      val execIn = "Hello".byteInputStream()
      val execOut: OutputStream = ByteArrayOutputStream()
      val execErr: OutputStream = ByteArrayOutputStream()

      val exec: ExecWatch = client
         .pods()
         .inNamespace(container.getNamespace().value)
         .withName("to compile"/*runtime.getPodName()*/)
         .inContainer(runtime.getName().value)
         .readingInput(execIn)
         .writingOutput(execOut)
         .writingError(execErr)
         .withTTY() //Optional
         .exec("cat")

      TimeUnit.SECONDS.sleep(30)

      log.debug("Exec stdout: $execOut")
      log.debug("Exec stderr: $execErr")
   }

}