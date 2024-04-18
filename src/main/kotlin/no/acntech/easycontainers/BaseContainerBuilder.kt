package no.acntech.easycontainers

import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.output.OutputLineCallback
import no.acntech.easycontainers.util.collections.prettyPrint
import no.acntech.easycontainers.util.text.NEW_LINE
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Abstract base class for container builders.
 *
 * @param SELF the type of the concrete builder that extends this class.
 * @property log the logger instance.
 * @property executionMode the execution mode of the container.
 * @property name the name of the container.
 * @property namespace the namespace of the container.
 * @property labels the labels associated with the container.
 * @property env the environment variables of the container.
 * @property command the command to be executed in the container.
 * @property args the arguments for the command.
 * @property image the image URL of the container.
 * @property exposedPorts the ports exposed by the container.
 * @property portMappings the port mappings for the container.
 * @property containerFiles the files in the container.
 * @property volumes the volumes attached to the container.
 * @property cpuRequest the CPU request of the container.
 * @property cpuLimit the CPU limit of the container.
 * @property memoryRequest the memory request of the container.
 * @property memoryLimit the memory limit of the container.
 * @property networkName the network name of the container.
 * @property isEphemeral indicates if the container is ephemeral.
 * @property outputLineCallback the callback for handling output lines.
 * @property maxLifeTime the maximum lifetime of the container.
 * @property customProperties the custom properties of the container.
 * @property containerPlatformType the type of the container platform.
 */
abstract class BaseContainerBuilder<SELF : BaseContainerBuilder<SELF>> : ContainerBuilder<SELF> {

   protected val log: Logger = LoggerFactory.getLogger(javaClass)

   internal var executionMode = ExecutionMode.SERVICE

   internal var name: ContainerName = ContainerName.of(UUID.randomUUID().toString())

   internal var namespace: Namespace = Namespace.DEFAULT

   internal var labels: MutableMap<LabelKey, LabelValue> = mutableMapOf()

   internal val env: MutableMap<EnvVarKey, EnvVarValue> = mutableMapOf()

   internal var command: Executable? = null

   internal var args: Args? = null

   internal lateinit var image: ImageURL

   internal val exposedPorts: MutableMap<PortMappingName, NetworkPort> = mutableMapOf()

   internal val portMappings: MutableMap<NetworkPort, NetworkPort> = mutableMapOf()

   internal val containerFiles: MutableMap<ContainerFileName, ContainerFile> = mutableMapOf() // name -> ConfigMapVolume

   internal val volumes: MutableList<Volume> = mutableListOf()

   internal var cpuRequest: CPU? = null

   internal var cpuLimit: CPU? = null

   internal var memoryRequest: Memory? = null

   internal var memoryLimit: Memory? = null

   internal var networkName: NetworkName? = null

   internal var isEphemeral: Boolean = true

   internal var outputLineCallback: OutputLineCallback = OutputLineCallback { _ -> }

   internal var maxLifeTime: Duration? = null

   internal var customProperties: MutableMap<String, Any> = mutableMapOf()

   internal var containerPlatformType = ContainerPlatformType.DOCKER

   override fun withContainerPlatformType(type: ContainerPlatformType): SELF {
      this.containerPlatformType = type
      return self()
   }

   override fun withExecutionMode(executionMode: ExecutionMode): SELF {
      this.executionMode = executionMode
      return self()
   }

   override fun withName(name: ContainerName): SELF {
      this.name = name
      return self()
   }

   override fun withNamespace(namespace: Namespace): SELF {
      this.namespace = namespace
      return self()
   }

   override fun withLabel(key: LabelKey, value: LabelValue): SELF {
      labels[key] = value
      return self()
   }

   override fun withEnv(key: String, value: String): SELF {
      return withEnv(EnvVarKey(key), EnvVarValue(value))
   }

   override fun withEnv(key: EnvVarKey, value: EnvVarValue): SELF {
      env[key] = value
      return self()
   }

   override fun withEnv(env: Map<EnvVarKey, EnvVarValue>): SELF {
      this.env.putAll(env)
      return self()
   }

   override fun withCommand(command: Executable): SELF {
      this.command = command
      return self()
   }

   override fun withArgs(args: Args): SELF {
      this.args = args
      return self()
   }

   override fun withImage(image: ImageURL): SELF {
      this.image = image
      return self()
   }

   override fun withExposedPort(name: PortMappingName, port: NetworkPort): SELF {
      exposedPorts[name] = port
      return self()
   }

   override fun withPortMapping(port: NetworkPort, mappedPort: NetworkPort): SELF {
      require(port in exposedPorts.values) { "Port '$port' cannot be mapped if not exposed" }
      portMappings[port] = mappedPort
      return self()
   }

   override fun withNetworkName(networkName: NetworkName): SELF {
      this.networkName = networkName
      return self()
   }

   override fun withCpuRequest(cpuRequest: CPU): SELF {
      this.cpuRequest = cpuRequest
      return self()
   }

   override fun withCpuLimit(cpuLimit: CPU): SELF {
      this.cpuLimit = cpuLimit
      return self()
   }

   override fun withMemoryRequest(memoryRequest: Memory): SELF {
      this.memoryRequest = memoryRequest
      return self()
   }

   override fun withMemoryLimit(memoryLimit: Memory): SELF {
      this.memoryLimit = memoryLimit
      return self()
   }

   override fun withIsEphemeral(ephemeral: Boolean): SELF {
      this.isEphemeral = ephemeral
      return self()
   }

   override fun withOutputLineCallback(outputLineCallback: OutputLineCallback): SELF {
      this.outputLineCallback = outputLineCallback
      return self()
   }

   override fun withContainerFile(file: ContainerFile): SELF {
      containerFiles[file.name] = file
      return self()
   }

   override fun withVolume(name: VolumeName, mountPath: UnixDir): SELF {
      volumes.add(Volume(name, mountPath))
      return self()
   }

   override fun withVolume(volume: Volume): SELF {
      volumes.add(volume)
      return self()
   }

   override fun withMaxLifeTime(maxLifeTime: Duration): SELF {
      this.maxLifeTime = maxLifeTime
      return self()
   }

   override fun withMaxLifeTime(value: Long, unit: ChronoUnit): SELF {
      this.maxLifeTime = Duration.of(value, unit)
      return self()
   }

   override fun withCustomProperty(key: String, value: Any): SELF {
      customProperties[key] = value
      return self()
   }


   protected open fun self(): SELF {
      @Suppress("UNCHECKED_CAST")
      return this as SELF
   }

   override fun toString(): String {
      return ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
         .append("name", name)
         .append("namespace", namespace)
         .append("labels", labels)
         .append("env", env)
         .append("command", command)
         .append("args", args)
         .append("image", image)
         .append("exposedPorts", exposedPorts)
         .append("mappedPorts", portMappings)
         .append("isEphemeral", isEphemeral)
         .append("maxLifeTime", maxLifeTime)
         .append("outputLineCallback", outputLineCallback)
         .append("customProperties$NEW_LINE", customProperties.prettyPrint(sortKeys = true))
         .append("networkName", networkName)
         .append("cpuRequest", cpuRequest)
         .append("cpuLimit", cpuLimit)
         .append("memoryRequest", memoryRequest)
         .append("memoryLimit", memoryLimit)
         .append("volumes", volumes)
         .append("containerFiles", containerFiles)
         .toString()
   }

}