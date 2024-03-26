package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder

object K8sClientFactory {

   fun createDefaultClient(): KubernetesClient {
      return KubernetesClientBuilder().build()
   }

}