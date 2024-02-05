package no.acntech.easycontainers

import no.acntech.easycontainers.docker.DockerImageBuilder
import no.acntech.easycontainers.k8s.K8sImageBuilder

/**
 * The ContainerFactory class is responsible for creating container instances using a builder pattern.
 */
object ContainerFactory {

   const val PROP_HOST_SHARE_ROOT = "host.share.root"

   /**
    * Creates a container using the builder pattern.
    *
    * @param block The block of code that configures the container using the ContainerBuilder instance.
    * @return The configured ContainerBuilder instance.
    */
   fun container(block: ContainerBuilder.() -> Unit): ContainerBuilder {
      val builder = ContainerBuilder()
      builder.block()
      return builder
   }

   /**
    * Builds a Docker container using the provided block of code.
    *
    * @param block a lambda expression with a receiver of type [ContainerBuilder]. This lambda
    *              is used to configure the Docker container by invoking methods on the receiver object.
    *
    * @return a [ContainerBuilder] object representing the configured Docker container.
    */
   fun dockerContainer(block: ContainerBuilder.() -> Unit): ContainerBuilder {
      return container {
         withType(ContainerType.DOCKER)
         block()
      }
   }

   /**
    * Creates a Kubernetes container with the provided configuration.
    *
    * @param block a lambda function that accepts a [ContainerBuilder] and allows configuring the container
    * @return a [ContainerBuilder] instance representing the Kubernetes container with the applied configuration
    */
   fun kubernetesContainer(block: ContainerBuilder.() -> Unit): ContainerBuilder {
      return container {
         withType(ContainerType.KUBERNETES)
         block()
      }
   }

   /**
    * Builds an instance of ImageBuilder based on the provided container type.
    *
    * @param type the type of container, defaults to ContainerType.KUBERNETES
    * @return an instance of ImageBuilder
    */
   fun imageBuilder(type: ContainerType = ContainerType.KUBERNETES): ImageBuilder {
      return when (type) {
         ContainerType.DOCKER -> DockerImageBuilder()
         ContainerType.KUBERNETES -> K8sImageBuilder()
      }
   }

}