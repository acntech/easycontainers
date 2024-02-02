package no.acntech.easycontainers

import no.acntech.easycontainers.docker.DockerImageBuilder
import no.acntech.easycontainers.k8s.K8sImageBuilder

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

   fun imageBuilder(type: ContainerType = ContainerType.KUBERNETES): ImageBuilder {
      return when (type) {
         ContainerType.DOCKER -> DockerImageBuilder()
         ContainerType.KUBERNETES -> K8sImageBuilder()
      }
   }

}