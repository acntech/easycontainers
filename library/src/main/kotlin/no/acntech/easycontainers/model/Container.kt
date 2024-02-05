package no.acntech.easycontainers.model

/**
 * Represents a container.
 *
 * This interface extends the [Runnable] interface, allowing the container to be executed as a separate thread.
 */
interface Container : Runnable {

   enum class State {
      CREATED,
      STARTED,
      RUNNING,
      FAILED,
      UNKNOWN,
      STOPPED,
      REMOVED
   }

   fun getState(): State

   // Name

   /**
    * Returns the name of the container.
    *
    * @return the name of the container as a ContainerName object
    */
   fun getName(): ContainerName

   /**
    * Returns the namespace of the container.
    */
   fun getNamespace(): Namespace


   /**
    * Retrieves the labels associated with the container.
    *
    * @return a map of LabelKey to LabelValue representing the labels
    */
   fun getLabels(): Map<LabelKey, LabelValue>

   // Environment

   /**
    * Retrieves the environment variables associated with the container.
    *
    * @return a map containing the environment variable keys and values
    */
   fun getEnv(): Map<EnvVarKey, EnvVarValue>

   // Command

   /**
    * Retrieves the executable command for the container.
    *
    * @return the executable command as an Executable object, or null if no command is specified
    */
   fun getCommand(): Executable?

   /**
    * Retrieves the command arguments for the container.
    *
    * @return the command arguments as an instance of Args, or null if there are no arguments.
    */
   fun getArgs(): Args?

   // Image

   /**
    * Retrieves the image for the container.
    *
    * @return the URL of the image
    */
   fun getImage(): ImageURL

   // Ports

   /**
    * Returns the list of network ports exposed by the container.
    *
    * @return the list of exposed network ports as a List of NetworkPort objects
    */
   fun getExposedPorts(): List<NetworkPort>

   /**
    * Returns the mapped port for the specified network port.
    *
    * @param port the network port for which to retrieve the mapped port
    * @return the mapped port as a NetworkPort object
    */
   fun getMappedPort(port: NetworkPort): NetworkPort

   /**
    * Retrieves the port mappings for the container.
    *
    * @return a map of NetworkPort to NetworkPort representing the port mappings.
    */
   fun getPortMappings(): Map<NetworkPort, NetworkPort>

   /**
    * Checks if the given network port has a mapping defined in the container's port mappings.
    *
    * @param port The network port to check
    * @return true if the port has a mapping, false otherwise
    */
   fun hasPortMapping(port: NetworkPort): Boolean {

      return getPortMappings().containsKey(port)
   }

   /**
    * Checks whether the container is ephemeral.
    *
    * @return true if the container is ephemeral, false otherwise
    */
// Ephemerality
   fun isEphemeral(): Boolean

   /**
    * Retrieves the host of the container.
    *
    * @return the host of the container as a [Host] object, or null if the host is not set
    */
// Host
   fun getHost(): Host?

   // Lifecycle

   /**
    * Stops the container.
    *
    * This method stops the execution of the container. If the container is already stopped or removed, this method has no effect.
    */
   fun stop()

   /**
    * Stops the container.
    */
   fun kill() {
      stop()
   }

   /**
    * Removes the container.
    *
    * This method removes the container from the system. After calling this method, the container will no longer exist.
    */
   fun remove()

}