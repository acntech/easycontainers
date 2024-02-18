package no.acntech.easycontainers.k8s

import io.fabric8.kubernetes.client.KubernetesClient
import no.acntech.easycontainers.AbstractContainer
import no.acntech.easycontainers.ContainerBuilder

internal abstract class AbstractK8sContainer(
   builder: ContainerBuilder,
   protected val client: KubernetesClient = K8sClientFactory.createDefaultClient(),
) : AbstractContainer(builder) {


}