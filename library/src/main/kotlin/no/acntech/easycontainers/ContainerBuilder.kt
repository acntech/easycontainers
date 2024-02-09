package no.acntech.easycontainers

import no.acntech.easycontainers.docker.DockerContainer
import no.acntech.easycontainers.k8s.K8sContainer
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.output.OutputLineCallback
import no.acntech.easycontainers.util.text.NEW_LINE
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Builder for configuring and building a Docker of Kubernetes container.
 */
class ContainerBuilder {

   companion object {
      private val log: Logger = LoggerFactory.getLogger(ContainerBuilder::class.java)
   }

   var containerType: ContainerType = ContainerType.KUBERNETES

   var name: ContainerName = ContainerName.of(UUID.randomUUID().toString())

   var namespace: Namespace = Namespace.DEFAULT

   var labels: MutableMap<LabelKey, LabelValue> = mutableMapOf()

   val env: MutableMap<EnvVarKey, EnvVarValue> = mutableMapOf()

   var command: Executable? = null

   var args: Args? = null

   lateinit var image: ImageURL

   /**
    * Exposed ports for the container.
    * Example: `exposedPorts(PortMappingName.HTTP, NetworkPort.HTTP)`
    */
   val exposedPorts: MutableMap<PortMappingName, NetworkPort> = mutableMapOf()

   /**
    * Port mappings for the container.
    * Example: `portMappings(NetworkPort.HTTP, NetworkPort.of(8080))`
    */
   val portMappings: MutableMap<NetworkPort, NetworkPort> = mutableMapOf()

   val containerFiles: MutableMap<ContainerFileName, ContainerFile> = mutableMapOf() // name -> ConfigMapVolume

   val volumes: MutableList<Volume> = mutableListOf()

   var cpuRequest: CPU? = null

   var cpuLimit: CPU? = null

   var memoryRequest: Memory? = null

   var memoryLimit: Memory? = null

   var networkName: NetworkName? = null

   var isEphemeral: Boolean = true

   var outputLineCallback: OutputLineCallback = OutputLineCallback { _ -> }

   var maxLifeTime: Duration? = null

   var customProperties: MutableMap<String, Any> = mutableMapOf()

   /**
    * Sets the container type for the container builder.
    *
    * @param type The container type to be set.
    * @return The container builder with the specified container type.
    */
   fun withType(type: ContainerType): ContainerBuilder {
      this.containerType = type
      return this
   }

   /**
    * Sets the name of the container.
    *
    * @param name the name of the container
    * @return the ContainerBuilder with the updated name
    */
   fun withName(name: ContainerName): ContainerBuilder {
      this.name = name
      return this
   }

   /**
    * Sets the namespace for the ContainerBuilder.
    *
    * @param namespace The namespace to set.
    * @return The updated ContainerBuilder instance.
    */
   fun withNamespace(namespace: Namespace): ContainerBuilder {
      this.namespace = namespace
      return this
   }

   /**
    * Adds a label to the container.
    *
    * @param key The key of the label.
    * @param value The value of the label.
    * @return The updated ContainerBuilder instance.
    */
   fun withLabel(key: LabelKey, value: LabelValue): ContainerBuilder {
      labels[key] = value
      return this
   }

   /**
    * Sets an environment variable with the specified key and value.
    *
    * @param key The key of the environment variable.
    * @param value The value of the environment variable.
    * @return The ContainerBuilder object with the environment variable set.
    */
   fun withEnv(key: String, value: String): ContainerBuilder {
      return withEnv(EnvVarKey(key), EnvVarValue(value))
   }

   /**
    * Adds an environment variable with the given key and value to the container.
    *
    * @param key the environment variable key
    * @param value the environment variable value
    * @return the updated ContainerBuilder instance
    */
   fun withEnv(key: EnvVarKey, value: EnvVarValue): ContainerBuilder {
      env[key] = value
      return this
   }

   /**
    * Sets the environment variables for the container. Any existing environment variables
    * will be replaced by the provided ones.
    *
    * @param env a map of environment variable keys and values
    * @return the updated ContainerBuilder instance
    */
   fun withEnv(env: Map<EnvVarKey, EnvVarValue>): ContainerBuilder {
      this.env.putAll(env)
      return this
   }

   /**
    * Sets the command for the container.
    *
    * @param command the executable command to be set
    * @return the updated instance of ContainerBuilder
    */
   fun withCommand(command: Executable): ContainerBuilder {
      this.command = command
      return this
   }

   /**
    * Sets the command arguments for the container.
    *
    * @param args The arguments to set for the container.
    * @return The updated ContainerBuilder object.
    */
   fun withArgs(args: Args): ContainerBuilder {
      this.args = args
      return this
   }

   /**
    * Set the image to use for the container.
    *
    * @param image The image to use for the container.
    * @return The updated ContainerBuilder with the specified image.
    */
   fun withImage(image: ImageURL): ContainerBuilder {
      this.image = image
      return this
   }

   /**
    * Adds an exposed port to the container configuration.
    *
    * @param name The name of the port mapping.
    * @param port The network port to expose.
    * @return The updated ContainerBuilder instance.
    */
   fun withExposedPort(name: PortMappingName, port: NetworkPort): ContainerBuilder {
      exposedPorts[name] = port
      return this
   }

   /**
    * Maps a container exposed network port to another network port.
    *
    * @param port The network port to be mapped.
    * @param mappedPort The mapped network port.
    * @return The updated instance of `ContainerBuilder`.
    * @throws IllegalArgumentException if the `port` is not exposed.
    */
   fun withPortMapping(port: NetworkPort, mappedPort: NetworkPort): ContainerBuilder {
      require(port in exposedPorts.values) { "Port '$port' cannot be mapped if not exposed" }
      portMappings[port] = mappedPort
      return this
   }

   /**
    * Sets the network name for the container. Only applicable for Docker containers - will create a bridge network with the
    * given name allowing containers on the same network to communicate with each other.
    *
    * @param networkName The network name to set for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withNetworkName(networkName: NetworkName): ContainerBuilder {
      this.networkName = networkName
      return this
   }

   /**
    * Sets the CPU request for the container.
    *
    * @param cpuRequest The CPU request for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withCpuRequest(cpuRequest: CPU): ContainerBuilder {
      this.cpuRequest = cpuRequest
      return this
   }

   /**
    * Sets the CPU limit for the container.
    *
    * @param cpuLimit the CPU limit to set for the container
    * @return the updated ContainerBuilder instance
    */
   fun withCpuLimit(cpuLimit: CPU): ContainerBuilder {
      this.cpuLimit = cpuLimit
      return this
   }

   /**
    * Sets the memory request for the container.
    *
    * @param memoryRequest the memory request value for the container
    * @return the ContainerBuilder object with the memory request set
    */
   fun withMemoryRequest(memoryRequest: Memory): ContainerBuilder {
      this.memoryRequest = memoryRequest
      return this
   }

   /**
    * Sets the memory limit for the container.
    *
    * @param memoryLimit The memory limit to set for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withMemoryLimit(memoryLimit: Memory): ContainerBuilder {
      this.memoryLimit = memoryLimit
      return this
   }

   /**
    * Sets the ephemeral property of the container.
    *
    * @param ephemeral true if the container is ephemeral, false otherwise
    * @return the instance of ContainerBuilder
    */
   fun withIsEphemeral(ephemeral: Boolean): ContainerBuilder {
      this.isEphemeral = ephemeral
      return this
   }

   /**
    * Sets the line callback for logging purposes.
    *
    * @param outputLineCallback The line callback to be used for logging.
    * @return The instance of ContainerBuilder with the line callback set.
    */
   fun withLogLineCallback(outputLineCallback: OutputLineCallback): ContainerBuilder {
      this.outputLineCallback = outputLineCallback
      return this
   }

   /**
    * Adds a container file to the builder.
    *
    * @param name The name of the file in the container
    * @param path The path in the container where the file will be mounted.
    * @param data The content of the file as a key-value map
    * @param keyValSeparator The separator between the key and value in the content of the file (default is ": ")
    */
   fun withContainerFile(name: ContainerFileName, path: UnixDir, data: Map<String, String>, keyValSeparator: String = ": ") {
      val content = data.entries.joinToString(NEW_LINE) { (key, value) -> "$key$keyValSeparator$value" }
      containerFiles[name] = ContainerFile(name, path, content)
   }

   /**
    * Adds a file to the container with the specified name, path, and content.
    * This method creates a new instance of the ContainerFile class and stores it in the containerFiles map.
    *
    * @param name The name of the file in the container
    * @param path The path in the container where the file will be mounted.
    * @param content The content of the file
    */
   fun withContainerFile(name: ContainerFileName, path: UnixDir, content: String) {
      containerFiles[name] = ContainerFile(name, path, content)
   }

   /**
    * Adds a volume to the container builder.
    *
    * @param name The name of the volume
    * @param mountPath The path in the container where the volume will be mounted
    * @return The updated container builder
    */
   fun withVolume(name: VolumeName, mountPath: UnixDir): ContainerBuilder {
      volumes.add(Volume(name, mountPath))
      return this
   }

   /**
    * Adds a volume to the container.
    *
    * @param volume The volume to be added or mapped into the container.
    * @return This ContainerBuilder instance.
    */
   fun withVolume(volume: Volume): ContainerBuilder {
      volumes.add(volume)
      return this
   }

   /**
    * Sets the maximum lifetime for the container.
    *
    * @param maxLifeTime The maximum lifetime for the container as a Duration object.
    * @return The updated ContainerBuilder instance.
    */
   fun withMaxLifeTime(maxLifeTime: Duration): ContainerBuilder {
      this.maxLifeTime = maxLifeTime
      return this
   }

   /**
    * Sets the maximum lifetime for the container.
    *
    * @param value The value of the duration for the maximum lifetime.
    * @param unit The unit of the duration for the maximum lifetime.
    * @return The updated ContainerBuilder instance.
    */
   fun withMaxLifeTime(value: Long, unit: ChronoUnit): ContainerBuilder {
      this.maxLifeTime = Duration.of(value, unit)
      return this
   }

   /**
    * Adds a custom property to the container builder.
    *
    * @param key the key of the custom property
    * @param value the value of the custom property
    * @return the container builder instance
    */
   fun withCustomProperty(key: String, value: Any): ContainerBuilder {
      customProperties[key] = value
      return this
   }

   /**
    * Builds the container.
    *
    * @return The built container.
    * @throws ContainerException if the image is not initialized.
    */
   fun build(): Container {
      if (!(::image.isInitialized)) {
         throw ContainerException("Image is required")
      }

      return when (containerType) {
         ContainerType.DOCKER -> DockerContainer(this)
         ContainerType.KUBERNETES -> K8sContainer(this)
      }.also {
         log.debug("Container created: {}", it)
      }
   }

   /**
    * Returns a string representation of the object.
    *
    * This method overrides the default `toString` method to provide a more detailed string representation of the object.
    * The string representation includes the container type, name, image, namespace, environment, command, arguments,
    * exposed ports, port mappings, ephemeral status, and container files.
    * The string representation is generated using the ToStringBuilder class from the Apache Commons Lang library.
    *
    * @return A string representation of the object.
    */
   override fun toString(): String {
      return ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
         .append(this::containerType.name, containerType)
         .append(this::name.name, name)
         .append(this::image.name, image)
         .append(this::namespace.name, namespace)
         .append(this::env.name, env)
         .append(this::command.name, command)
         .append(this::args.name, args)
         .append(this::exposedPorts.name, exposedPorts)
         .append(this::portMappings.name, portMappings)
         .append(this::isEphemeral.name, isEphemeral)
         .append(this::containerFiles.name + NEW_LINE, containerFiles)
         .toString()
   }
}

