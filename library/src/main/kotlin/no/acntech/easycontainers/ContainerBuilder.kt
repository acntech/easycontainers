package no.acntech.easycontainers

import no.acntech.easycontainers.docker.DockerContainer
import no.acntech.easycontainers.k8s.ServiceContainer
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

   var executionMode = ExecutionMode.SERVICE

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
    * @return The updated ContainerBuilder instance.
    */
   fun withType(type: ContainerType): ContainerBuilder {
      this.containerType = type
      return this
   }

   /**
    * Sets the execution mode for the container.
    * @param executionMode The execution mode to be set.
    * @return The updated ContainerBuilder instance.
    */
   fun withExecutionMode(executionMode: ExecutionMode): ContainerBuilder {
      this.executionMode = executionMode
      return this
   }

   /**
    * Sets the name of the container.
    *
    * @param name the name of the container
    * @return The updated ContainerBuilder instance.
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
    * @return The updated ContainerBuilder instance.
    */
   fun withEnv(key: String, value: String): ContainerBuilder {
      return withEnv(EnvVarKey(key), EnvVarValue(value))
   }

   /**
    * Adds an environment variable with the given key and value to the container.
    *
    * @param key the environment variable key
    * @param value the environment variable value
    * @return The updated ContainerBuilder instance.
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
    * @return The updated ContainerBuilder instance.
    */
   fun withEnv(env: Map<EnvVarKey, EnvVarValue>): ContainerBuilder {
      this.env.putAll(env)
      return this
   }

   /**
    * Sets the command for the container, analogous to Kubernetes' `command` and Docker's overridden `ENTRYPOINT`.
    * This method standardizes container execution across Kubernetes and Docker by specifying the executable
    * to run when the container starts. In Docker, this effectively neutralizes the `ENTRYPOINT` directive
    * by ensuring the command is executed directly, typically via `/bin/sh -c`, allowing for a seamless
    * integration of command execution across both platforms.
    * <p>
    * Note: In Docker, the actual neutralization of `ENTRYPOINT` and execution of the command
    * might be fully realized when `withArgs` is called, as it combines both command and arguments
    * into a single executable statement.
    * <p>
    * Note that if no command or arguments are specified, the container will use the default command, arguments and entrypoint
    * specified in the image.
    *
    * @param command the executable command to be set. In Kubernetes, this corresponds to the `command` field.
    *                In Docker, this is part of the command string executed by `/bin/sh -c`.
    * @return The updated ContainerBuilder instance.
    */
   fun withCommand(command: Executable): ContainerBuilder {
      this.command = command
      return this
   }

   /**
    * Sets the command arguments for the container, aligning with Kubernetes' `args` and Docker's `CMD`.
    * This method unifies the handling of command arguments in both Kubernetes and Docker. It ensures that
    * in Docker, the `ENTRYPOINT` is effectively neutralized by combining these arguments with the command
    * specified via `withCommand` to form a single command string. This string is then executed in a shell,
    * mirroring Kubernetes' behavior and providing a consistent command execution environment across both platforms.
    *
    * The invocation of this method completes the setup for command execution in Docker, ensuring that
    * both command and arguments are treated as a unified command line, similar to how Kubernetes processes
    * `command` and `args`.
    *
    * @param args The arguments to be used with the command. In Kubernetes, corresponds to the `args` provided
    *             to the `command`. In Docker, these are combined with the command to form a complete shell command.
    * @return The updated ContainerBuilder instance.
    */
   fun withArgs(args: Args): ContainerBuilder {
      this.args = args
      return this
   }

   /**
    * Set the image to use for the container.
    *
    * @param image The image to use for the container.
    * @return The updated ContainerBuilder instance.
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
    * @return The updated ContainerBuilder instance.
    * @throws IllegalArgumentException if the `port` is not exposed.
    */
   fun withPortMapping(port: NetworkPort, mappedPort: NetworkPort): ContainerBuilder {
      require(port in exposedPorts.values) { "Port '$port' cannot be mapped if not exposed" }
      portMappings[port] = mappedPort
      return this
   }

   /**
    * Configures the network mode for a container, specifically targeting Docker container environments. This method allows
    * setting the network to predefined modes such as "bridge", "host", "none", or to a user-defined network. Additionally,
    * it supports attaching the container to the network namespace of another container using the "container:&lt;name&gt;"
    * syntax, where "&lt;name&gt;" is the name or ID of another container.
    *
    * Predefined network modes include:
    * - "bridge": Creates a new network stack for the container on the Docker bridge network. This is the default network mode.
    * - "host": Uses the host's network stack, effectively bypassing Docker's networking layers.
    * - "none": Disables all networking for the container, isolating it completely.
    *
    * Custom network names can also be provided to connect the container to user-defined networks, enabling advanced
    * networking features such as network segmentation and custom network policies.
    *
    * Note: This setting is only applicable to Docker containers. If the container platform does not support Docker or
    * the specified network mode, this setting may be ignored or result in an error.
    *
    * @param networkName The network mode or custom network name to assign to the container. This parameter is of type
    *                    NetworkName, which encapsulates the network name value.
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
    * @return The updated ContainerBuilder instance.
    */
   fun withIsEphemeral(ephemeral: Boolean): ContainerBuilder {
      this.isEphemeral = ephemeral
      return this
   }

   /**
    * Sets the line callback for logging purposes.
    *
    * @param outputLineCallback The line callback to be used for logging.
    * @return The updated ContainerBuilder instance.
    */
   fun withOutputLineCallback(outputLineCallback: OutputLineCallback): ContainerBuilder {
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
    * @return The updated ContainerBuilder instance.
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
    * @return The updated ContainerBuilder instance.
    */
   fun withContainerFile(name: ContainerFileName, path: UnixDir, content: String) {
      containerFiles[name] = ContainerFile(name, path, content)
   }

   /**
    * Adds a volume to the container builder.
    *
    * @param name The name of the volume
    * @param mountPath The path in the container where the volume will be mounted
    * @return The updated ContainerBuilder instance.
    */
   fun withVolume(name: VolumeName, mountPath: UnixDir): ContainerBuilder {
      volumes.add(Volume(name, mountPath))
      return this
   }

   /**
    * Adds a volume to the container.
    *
    * @param volume The volume to be added or mapped into the container.
    * @return The updated ContainerBuilder instance.
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
    * @return The updated ContainerBuilder instance.
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
    * @return The built container.
    */
   fun build(): Container {
      if (!(::image.isInitialized)) {
         throw ContainerException("Image is required")
      }

      return when (containerType) {
         ContainerType.DOCKER -> DockerContainer(this)
         ContainerType.KUBERNETES -> ServiceContainer(this)
      }.also {
         log.debug("Container created: {}", it)
      }
   }

   /**
    * Returns a string representation of this ContainerBuilder.
    * @return A string representation of this ContainerBuilder.
    */
   override fun toString(): String {
      return ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
         .append(this::containerType.name, containerType)
         .append(this::executionMode.name, executionMode)
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

