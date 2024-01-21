package no.acntech.easycontainers

import no.acntech.easycontainers.k8s.K8sContainerImageBuilder

object ContainerFactory {

   fun container(block: ContainerBuilder.() -> Unit): ContainerBuilder {
      val builder = ContainerBuilder()
      builder.block()
      return builder
   }

   fun dockerContainer(block: ContainerBuilder.() -> Unit): ContainerBuilder {
      return container {
         withType(ContainerType.DOCKER)
         block()
      }
   }

   fun kubernetesContainer(block: ContainerBuilder.() -> Unit): ContainerBuilder {
      return container {
         withType(ContainerType.KUBERNETES)
         block()
      }
   }

   fun imageBuilder(type: ContainerType = ContainerType.KUBERNETES): ContainerImageBuilder {
      return when (type) {
         ContainerType.DOCKER -> throw UnsupportedOperationException("Docker image builder is not supported")
         ContainerType.KUBERNETES -> K8sContainerImageBuilder()
      }
   }

}