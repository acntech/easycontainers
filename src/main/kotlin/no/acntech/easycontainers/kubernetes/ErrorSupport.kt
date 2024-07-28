package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.ResourceNotFoundException
import io.fabric8.kubernetes.client.WatcherException
import no.acntech.easycontainers.ContainerException
import org.slf4j.Logger

object ErrorSupport {

   @Throws(ContainerException::class)
   fun handleK8sException(e: Exception, log: Logger) {
      when (e) {
         is KubernetesClientException, is ResourceNotFoundException, is WatcherException -> {
            val message = "Kubernetes error: ${e.message}"
            log.error(message, e)
            throw ContainerException(message, e)
         }

         is ContainerException -> throw e

         else -> {
            log.error("Unexpected exception '${e.javaClass.simpleName}:'${e.message}', re-throwing", e)
            throw e // Rethrow the exception if it's not one of the handled types
         }
      }
   }
}