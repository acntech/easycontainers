package no.acntech.easycontainers.k8s

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder

object K8sClientFactory {

   fun createDefaultClient(): KubernetesClient {
      return KubernetesClientBuilder().build()
   }

}