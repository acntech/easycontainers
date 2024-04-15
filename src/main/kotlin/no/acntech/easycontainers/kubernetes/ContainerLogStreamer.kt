package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.output.LineReader
import no.acntech.easycontainers.output.OutputLineCallback
import java.io.IOException
import java.util.concurrent.CancellationException

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
      try {
         lineReader.read()
      } catch(e: IOException) {
         when (e.cause) {
            is InterruptedException, is CancellationException -> {
               // The thread was interrupted or cancelled, so we can ignore this exception
            }
            else -> throw ContainerException("Failed to read log stream for pod '$podName' in namespace '$namespace'", e)
         }
      }
   }

   fun stop() {
      lineReader.stop()
   }

}