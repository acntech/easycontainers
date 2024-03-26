package no.acntech.easycontainers.model

import no.acntech.easycontainers.output.OutputLineCallback
import java.time.Duration
import java.time.temporal.ChronoUnit

interface ContainerBuilder<SELF : ContainerBuilder<SELF>> {

   /**
    * Sets the container runtime type.
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
    * Sets the name of the container.
    *
    * @param name the name of the container
    * @return The updated ContainerBuilder instance.
    */
   fun withName(name: ContainerName): SELF

   /**
    * Sets the namespace for the ContainerBuilder.
    *
    * @param namespace The namespace to set.
    * @return The updated ContainerBuilder instance.
    */
   fun withNamespace(namespace: Namespace): SELF

   /**
    * Adds a label to the container.
    *
    * @param key The key of the label.
    * @param value The value of the label.
    * @return The updated ContainerBuilder instance.
    */
   fun withLabel(key: LabelKey, value: LabelValue): SELF

   /**
    * Sets an environment variable with the specified key and value.
    *
    * @param key The key of the environment variable.
    * @param value The value of the environment variable.
    * @return The updated ContainerBuilder instance.
    */
   fun withEnv(key: String, value: String): SELF

   /**
    * Adds an environment variable with the given key and value to the container.
    *
    * @param key the environment variable key
    * @param value the environment variable value
    * @return The updated ContainerBuilder instance.
    */
   fun withEnv(key: EnvVarKey, value: EnvVarValue): SELF

   /**
    * Sets the environment variables for the container. Any existing environment variables
    * will be replaced by the provided ones.
    *
    * @param env a map of environment variable keys and values
    * @return The updated ContainerBuilder instance.
    */
   fun withEnv(env: Map<EnvVarKey, EnvVarValue>): SELF

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
   fun withCommand(command: Executable): SELF

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
   fun withArgs(args: Args): SELF

   /**
    * Set the image to use for the container.
    *
    * @param image The image to use for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withImage(image: ImageURL): SELF

   /**
    * Adds an exposed port to the container configuration.
    *
    * @param name The name of the port mapping.
    * @param port The network port to expose.
    * @return The updated ContainerBuilder instance.
    */
   fun withExposedPort(name: PortMappingName, port: NetworkPort): SELF

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
   fun withNetworkName(networkName: NetworkName): SELF

   /**
    * Sets the CPU request for the container.
    *
    * @param cpuRequest The CPU request for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withCpuRequest(cpuRequest: CPU): SELF

   /**
    * Sets the CPU limit for the container.
    *
    * @param cpuLimit the CPU limit to set for the container
    * @return the updated ContainerBuilder instance
    */
   fun withCpuLimit(cpuLimit: CPU): SELF

   /**
    * Sets the memory request for the container.
    *
    * @param memoryRequest the memory request value for the container
    * @return the ContainerBuilder object with the memory request set
    */
   fun withMemoryRequest(memoryRequest: Memory): SELF

   /**
    * Sets the memory limit for the container.
    *
    * @param memoryLimit The memory limit to set for the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withMemoryLimit(memoryLimit: Memory): SELF

   /**
    * Sets the ephemeral property of the container.
    *
    * @param ephemeral true if the container is ephemeral, false otherwise
    * @return The updated ContainerBuilder instance.
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
    * Adds a volume to the container builder.
    *
    * @param name The name of the volume
    * @param mountPath The path in the container where the volume will be mounted
    * @return The updated ContainerBuilder instance.
    */
   fun withVolume(name: VolumeName, mountPath: UnixDir): SELF

   /**
    * Adds a volume to the container.
    *
    * @param volume The volume to be added or mapped into the container.
    * @return The updated ContainerBuilder instance.
    */
   fun withVolume(volume: Volume): SELF

   /**
    * Sets the maximum lifetime for the container.
    *
    * @param maxLifeTime The maximum lifetime for the container as a Duration object.
    * @return The updated ContainerBuilder instance.
    */
   fun withMaxLifeTime(maxLifeTime: Duration): SELF

   /**
    * Sets the maximum lifetime for the container.
    *
    * @param value The value of the duration for the maximum lifetime.
    * @param unit The unit of the duration for the maximum lifetime.
    * @return The updated ContainerBuilder instance.
    */
   fun withMaxLifeTime(value: Long, unit: ChronoUnit): SELF

   /**
    * Adds a custom property to the container builder.
    *
    * @param key the key of the custom property
    * @param value the value of the custom property
    * @return The updated ContainerBuilder instance.
    */
   fun withCustomProperty(key: String, value: Any): SELF

   fun build(): Container
}