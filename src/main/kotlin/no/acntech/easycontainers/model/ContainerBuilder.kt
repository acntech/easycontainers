package no.acntech.easycontainers.model

import no.acntech.easycontainers.output.OutputLineCallback
import java.math.BigInteger
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * A builder interface for creating container configurations.
 *
 * @param SELF The type of the builder itself.
 */
interface ContainerBuilder<SELF : ContainerBuilder<SELF>> {

   /**
    *
    */
   fun withContainerPlatformType(type: ContainerPlatformType): SELF

   /**
    * Sets the execution mode for the container.
    *
    * @param executionMode The execution mode to be set.
    * @return The updated ContainerBuilder instance.
    */
   fun withExecutionMode(executionMode: ExecutionMode): SELF

   /**
    *
    */
   fun withName(name: ContainerName): SELF

   /**
    *
    */
   fun withName(name : String) : SELF {
      return withName(ContainerName.of(name))
   }

   /**
    * Sets the namespace for the ContainerBuilder.
    *
    * @param namespace The namespace to set.
    * @return The updated ContainerBuilder instance.
    */
   fun withNamespace(namespace: Namespace): SELF

   /**
    * Sets the namespace for the container.
    *
    * @param namespace The namespace to set for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withNamespace(namespace : String) : SELF {
      return withNamespace(Namespace.of(namespace))
   }

   /**
    * Adds a label to the container with the given key and value.
    *
    * @param key   the key of the label
    * @param value the value of the label
    * @return The updated ContainerBuilder instance.
    */
   fun withLabel(key: LabelKey, value: LabelValue): SELF


   /**
    * Adds a label with the provided key-value pair to the container.
    *
    * @param key the label key
    * @param value the label value
    * @return The updated ContainerBuilder instance.
    */
   fun withLabel(key: String, value: String): SELF {
      return withLabel(LabelKey.of(key), LabelValue.of(value))
   }

   /**
    * Adds an environment variable with the specified key and value.
    *
    * @param key The key of the environment variable.
    * @param value The value of the environment variable.
    * @return The updated ContainerBuilder instance.
    */
   fun withEnv(key: String, value: String): SELF {
      return withEnv(EnvVarKey.of(key), EnvVarValue.of(value))
   }

   /**
    * Sets an environment variable with the given key and value.
    *
    * @param key The key of the environment variable.
    * @param value The value of the environment variable.
    * @return The updated ContainerBuilder instance.
    */
   fun withEnv(key: EnvVarKey, value: EnvVarValue): SELF

   /**
    * Sets the environment variables for the container.
    *
    * @param env a map containing the environment variable keys and values
    * @return The updated ContainerBuilder instance.
    */
   fun withEnv(env: Map<EnvVarKey, EnvVarValue>): SELF

   /**
    * Sets the environment variables of the container using a map of string key-value pairs.
    *
    * @param env the environment variables as a map of string key-value pairs
    * @return The updated ContainerBuilder instance.
    */
   fun withEnvAsStringMap(env: Map<String, String>): SELF {
      return withEnv(env.mapKeys { EnvVarKey.of(it.key) }.mapValues { EnvVarValue.of(it.value) })
   }

   /**
    * Sets the command for the container.
    *
    * @param command the executable command to be set
    * @return The updated ContainerBuilder instance.
    */
   fun withCommand(command: Executable): SELF

   /**
    * Sets the command for the container.
    *
    * @param command the command to be set for the container
    * @return The updated ContainerBuilder instance.
    *
    * @see Executable
    */
   fun withCommand(command: String): SELF {
      return withCommand(Executable.of(command))
   }

   /**
    * Sets the command arguments for the container.
    *
    * @param args The command arguments to be set for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withArgs(args: Args): SELF

   /**
    * Sets the arguments for the container.
    *
    * @param args the arguments as an instance of Args.
    * @return The updated ContainerBuilder instance.
    */
   fun withArgs(args: List<String>): SELF {
      return withArgs(Args.of(args))
   }

   /**
    * Set the image to use for the container.
    *
    * @param image The image to use for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withImage(image: ImageURL): SELF

   /**
    * Sets the image for the container using the given image URL.
    *
    * @param image the image URL to set for the container
    * @return The updated ContainerBuilder instance.
    */
   fun withImage(image: String): SELF {
      return withImage(ImageURL.of(image))
   }

   /**
    * Adds an exposed port to the container configuration.
    *
    * @param name The name of the port mapping.
    * @param port The network port to expose.
    * @return The updated ContainerBuilder instance.
    */
   fun withExposedPort(name: PortMappingName, port: NetworkPort): SELF

   /**
    * Sets the exposed port for the container by specifying the name and port number.
    *
    * @param name the name of the port mapping
    * @param port the port number to expose
    * @return The updated ContainerBuilder instance.
    */
   fun withExposedPort(name: String, port: Int): SELF {
      return withExposedPort(PortMappingName.of(name), NetworkPort.of(port))
   }

   /**
    * Maps a container exposed network port to another network port.
    *
    * @param port The network port to be mapped.
    * @param mappedPort The mapped network port.
    * @return The updated ContainerBuilder instance.
    * @throws IllegalArgumentException if the `port` is not exposed.
    */
   fun withPortMapping(port: NetworkPort, mappedPort: NetworkPort): SELF

   /**
    * Sets the port mapping for the container.
    *
    * @param port The port of the container.
    * @param mappedPort The mapped port for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withPortMapping(port: Int, mappedPort: Int): SELF {
      return withPortMapping(NetworkPort.of(port), NetworkPort.of(mappedPort))
   }

   /**
    * Sets the network name for the container.
    *
    * @param networkName the network name to set
    * @return The updated ContainerBuilder instance.
    */
   fun withNetworkName(networkName: NetworkName): SELF

   /**
    * Sets the network name for the container.
    *
    * @param networkName the name of the network to be set
    * @return The updated ContainerBuilder instance.
    */
   fun withNetworkName(networkName: String): SELF {
      return withNetworkName(NetworkName.of(networkName))
   }

   /**
    * Sets the CPU request for the container.
    *
    * @param cpuRequest The CPU request for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withCpuRequest(cpuRequest: CPU): SELF

   /**
    * Sets the CPU request for the container.
    *
    * @param cpuRequest the CPU request for the container
    * @return The updated ContainerBuilder instance.
    */
   fun withCpuRequest(cpuRequest: Double): SELF {
      return withCpuRequest(CPU.of(cpuRequest))
   }

   /**
    * Sets the CPU request for the container.
    *
    * @param cpuRequest the CPU request for the container
    * @return The updated ContainerBuilder instance.
    */
   fun withCpuRequest(cpuRequest: String): SELF {
      return withCpuRequest(CPU.of(cpuRequest))
   }

   /**
    * Sets the CPU limit for the container.
    *
    * @param cpuLimit The CPU limit to be set for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withCpuLimit(cpuLimit: CPU): SELF

   /**
    * Sets the CPU limit for the container.
    *
    * @param cpuLimit The CPU limit as a [CPU] object.
    * @return The updated ContainerBuilder instance.
    */
   fun withCpuLimit(cpuLimit: Double): SELF {
      return withCpuLimit(CPU.of(cpuLimit))
   }

   /**
    * Sets the CPU limit for the container.
    * @param cpuLimit The CPU limit to set for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withCpuLimit(cpuLimit: String): SELF {
      return withCpuLimit(CPU.of(cpuLimit))
   }

   /**
    *
    */
   fun withMemoryRequest(memoryRequest: Memory): SELF

   /**
    * Performs the task with the given memory request.
    *
    * @param memoryRequest The memory request to be used.
    * @return The updated ContainerBuilder instance.
    */
   fun withMemoryRequest(memoryRequest: String): SELF {
      return withMemoryRequest(Memory.of(memoryRequest))
   }

   /**
    * Sets the memory request for the container.
    *
    * @param memoryRequest the memory request to be set
    * @return The updated ContainerBuilder instance.
    */
   fun withMemoryRequest(memoryRequest: BigInteger): SELF {
      return withMemoryRequest(Memory.of(memoryRequest))
   }

   /**
    * Sets the memory limit for the container.
    *
    * @param memoryLimit The memory limit to set for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withMemoryLimit(memoryLimit: Memory): SELF

   /**
    * Sets the memory limit for the current operation.
    *
    * @param memoryLimit the memory limit to be set. The memory limit should be specified as a string,
    *                    representing the amount of memory in a human-readable format. For example,
    *                    "1G" represents 1 gigabyte, "2M" represents 2 megabytes.
    * @return The updated ContainerBuilder instance.
    */
   fun withMemoryLimit(memoryLimit: String): SELF {
      return withMemoryLimit(Memory.of(memoryLimit))
   }

   /**
    * Sets the memory limit for the container.
    *
    * @param memoryLimit The memory limit for the container as a BigInteger.
    * @return The updated ContainerBuilder instance.
    */
   fun withMemoryLimit(memoryLimit: BigInteger): SELF {
      return withMemoryLimit(Memory.of(memoryLimit))
   }

   /**
    *
    */
   fun withIsEphemeral(ephemeral: Boolean): SELF

   /**
    * Sets the line callback for logging purposes.
    *
    * @param outputLineCallback The line callback to be used for logging.
    * @return The updated ContainerBuilder instance.
    */
   fun withOutputLineCallback(outputLineCallback: OutputLineCallback): SELF

   /**
    * Adds a container file to the builder.
    *
    * @param name The name of the file in the container
    * @param path The path in the container where the file will be mounted.
    * @param data The content of the file as a key-value map
    * @param keyValSeparator The separator between the key and value in the content of the file (default is ": ")
    * @return The updated ContainerBuilder instance.
    */
   fun withContainerFile(name: ContainerFileName, path: UnixDir, data: Map<String, String>, keyValSeparator: String = ": "): SELF

   /**
    * Adds a file to the container with the specified name, path, and content.
    * This method creates a new instance of the ContainerFile class and stores it in the containerFiles map.
    *
    * @param name The name of the file in the container
    * @param path The path in the container where the file will be mounted.
    * @param content The content of the file
    * @return The updated ContainerBuilder instance.
    */
   fun withContainerFile(name: ContainerFileName, path: UnixDir, content: String): SELF

   /**
    *
    */
   fun withVolume(name: VolumeName, mountPath: UnixDir): SELF

   /**
    * Sets the volume for the container.
    *
    * @param volume The volume to be added or mapped into the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withVolume(volume: Volume): SELF

   /**
    * Sets the maximum lifetime for the container.
    *
    * @param maxLifeTime The maximum lifetime of the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withMaxLifeTime(maxLifeTime: Duration): SELF

   /**
    * Sets the maximum lifetime for the container.
    *
    * @param value The value of the maximum lifetime.
    * @param unit The unit of time for the maximum lifetime.
    * @return The updated ContainerBuilder instance.
    */
   fun withMaxLifeTime(value: Long, unit: ChronoUnit): SELF

   /**
    * Adds a custom property to the container.
    *
    * @param key the key of the custom property
    * @param value the value of the custom property
    * @return The updated ContainerBuilder instance.
    */
   fun withCustomProperty(key: String, value: Any): SELF

   /**
    * This method is used to build a Container object.
    *
    * @return A Container object after building it.
    */
   fun build(): Container
}