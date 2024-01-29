package no.acntech.easycontainers.k8s

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import no.acntech.easycontainers.output.LineCallback
import no.acntech.easycontainers.output.LineReader

/**
 * Stream logs from a container in a Kubernetes pod. Implements [Runnable] and can be used in a [Thread].
 */
class ContainerLogStreamer(
   val podName: String,
   val namespace: String = K8sConstants.DEFAULT_NAMESPACE,
   private val client: KubernetesClient = KubernetesClientBuilder().build(),
   private val lineCallback: LineCallback = LineCallback(::println)

): Runnable {

   private val lineReader: LineReader

   init {
      val logOutput = client.pods().inNamespace(namespace).withName(podName).watchLog().output
      lineReader = LineReader(logOutput, lineCallback)
   }

   override fun run() {
      lineReader.read()
   }

   fun stop() {
      lineReader.stop()
   }

}