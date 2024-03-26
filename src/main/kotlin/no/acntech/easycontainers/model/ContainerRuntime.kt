package no.acntech.easycontainers.model

/**
 * Interface representing the runtime of a container - either Docker or Kubernetes.
 */
interface ContainerRuntime {

   /**
    * Retrieves the type of the container platform.
    *
    * @return the type of the container platform as a ContainerPlatformType enum value
    */
   fun getType(): ContainerPlatformType

   /**
    * Retrieves the container associated with the runtime.
    *
    * @return the container as a Container object
    */
   fun getContainer(): Container

   /**
    * Starts the execution of the container.
    */
   fun start()

   /**
    * Stops the container runtime.
    */
   fun stop()

   /**
    * Kills the container by calling the stop() method.
    * This method is used to forcefully terminate the execution of the container.
    * After calling this method, the container will be in the stopped state.
    * It is advisable to call the delete() method after killing the container.
    */
   fun kill() {
      stop()
   }

   /**
    * Deletes the container with an optional force flag.
    *
    * @param force if true, force the deletion of the container (default is false)
    */
   fun delete(force: Boolean = false)
}