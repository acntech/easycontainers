package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import no.acntech.easycontainers.output.LineReader
import no.acntech.easycontainers.output.OutputLineCallback

/**
 * Stream logs from a container in a Kubernetes pod. Implements [Runnable] and can be used in a [Thread].
 */
class ContainerLogStreamer(
   val podName: String,
   val namespace: String = K8sConstants.DEFAULT_NAMESPACE,
   client: KubernetesClient = KubernetesClientBuilder().build(),
   outputLineCallback: OutputLineCallback = OutputLineCallback(::println),
) : Runnable {

   private val lineReader: LineReader

   init {
      val logOutput = client.pods().inNamespace(namespace).withName(podName).watchLog().output
      lineReader = LineReader(logOutput, outputLineCallback)
   }

   override fun run() {
      lineReader.read()
   }

   fun stop() {
      lineReader.stop()
   }

}