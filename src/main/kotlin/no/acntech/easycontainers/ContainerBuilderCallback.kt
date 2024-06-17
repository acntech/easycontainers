package no.acntech.easycontainers

import no.acntech.easycontainers.model.ContainerBuilder

/**
 * An interface for defining a callback to configure a container builder.
 * Implement the [ContainerBuilderCallback] interface to provide custom configuration logic for a container builder.
 *
 * @param T the type of container builder
 */
interface ContainerBuilderCallback {

   /**
    * Configures a container builder.
    *
    * @param builder the container builder to configure
    */
   fun configure(builder: ContainerBuilder<*>)

}