package no.acntech.easycontainers

import no.acntech.easycontainers.docker.DockerContainer
import no.acntech.easycontainers.k8s.K8sContainer
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.output.LineCallback
import no.acntech.easycontainers.util.text.NEW_LINE
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Builder for configuring and building a container.
 */
class ContainerBuilder {

   data class ConfigFile(
      val mountPath: UnixDir,
      val content: String,
   )

   data class Volume(
      val name: VolumeName,
      val mountPath: UnixDir,
   )

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

   val exposedPorts: MutableMap<PortMappingName, NetworkPort> = mutableMapOf()

   val portMappings: MutableMap<NetworkPort, NetworkPort> = mutableMapOf()

   val configFiles: MutableMap<ConfigFileKey, ConfigFile> = mutableMapOf() // name -> ConfigMapVolume

   val volumes: MutableList<Volume> = mutableListOf()

   var cpuRequest: CPU? = null

   var cpuLimit: CPU? = null

   var memoryRequest: Memory? = null

   var memoryLimit: Memory? = null

   var isEphemeral: Boolean = true

   var lineCallback: LineCallback = LineCallback { _ -> }

   var maxLifeTime: Duration? = null

   var customProperties: MutableMap<String, Any> = mutableMapOf()

   fun withType(type: ContainerType): ContainerBuilder {
      this.containerType = type
      return this
   }

   fun withName(name: ContainerName): ContainerBuilder {
      this.name = name
      return this
   }

   fun withNamespace(namespace: Namespace): ContainerBuilder {
      this.namespace = namespace
      return this
   }

   fun withLabel(key: LabelKey, value: LabelValue): ContainerBuilder {
      labels[key] = value
      return this
   }

   fun withEnv(key: String, value: String): ContainerBuilder {
      return withEnv(EnvVarKey(key), EnvVarValue(value))
   }

   fun withEnv(key: EnvVarKey, value: EnvVarValue): ContainerBuilder {
      env[key] = value
      return this
   }

   fun withEnv(env: Map<EnvVarKey, EnvVarValue>): ContainerBuilder {
      this.env.putAll(env)
      return this
   }

   fun withCommand(command: Executable): ContainerBuilder {
      this.command = command
      return this
   }

   fun withArgs(args: Args): ContainerBuilder {
      this.args = args
      return this
   }

   fun withImage(image: ImageURL): ContainerBuilder {
      this.image = image
      return this
   }

   fun withExposedPort(name: PortMappingName, port: NetworkPort): ContainerBuilder {
      exposedPorts[name] = port
      return this
   }

   fun withPortMapping(port: NetworkPort, mappedPort: NetworkPort): ContainerBuilder {
      require(port in exposedPorts.values) { "Port '$port' cannot be mapped if not exposed" }
      portMappings[port] = mappedPort
      return this
   }

   fun withCpuRequest(cpuRequest: CPU): ContainerBuilder {
      this.cpuRequest = cpuRequest
      return this
   }

   fun withCpuLimit(cpuLimit: CPU): ContainerBuilder {
      this.cpuLimit = cpuLimit
      return this
   }

   fun withMemoryRequest(memoryRequest: Memory): ContainerBuilder {
      this.memoryRequest = memoryRequest
      return this
   }

   fun withMemoryLimit(memoryLimit: Memory): ContainerBuilder {
      this.memoryLimit = memoryLimit
      return this
   }

   fun withIsEphemeral(ephemeral: Boolean): ContainerBuilder {
      this.isEphemeral = ephemeral
      return this
   }

   fun withLogLineCallback(lineCallback: LineCallback): ContainerBuilder {
      this.lineCallback = lineCallback
      return this
   }

   fun withConfigFile(name: ConfigFileKey, path: UnixDir, data: Map<String, String>, keyValSeparator: String = ": ") {
      val content = data.entries.joinToString(NEW_LINE) { (key, value) -> "$key$keyValSeparator$value" }
      configFiles[name] = ConfigFile(path, content)
   }

   fun withConfigFile(name: ConfigFileKey, path: UnixDir, content: String) {
      configFiles[name] = ConfigFile(path, content)
   }

   fun withVolume(name: VolumeName, mountPath: UnixDir): ContainerBuilder {
      volumes.add(Volume(name, mountPath))
      return this
   }

   fun withMaxLifeTime(maxLifeTime: Duration): ContainerBuilder {
      this.maxLifeTime = maxLifeTime
      return this
   }

   fun withMaxLifeTime(value: Long, unit: ChronoUnit): ContainerBuilder {
      this.maxLifeTime = Duration.of(value, unit)
      return this
   }

   fun withCustomProperty(key: String, value: Any): ContainerBuilder {
      customProperties[key] = value
      return this
   }

   /**
    * Build the container.
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
         .append(this::configFiles.name + NEW_LINE, configFiles)
         .toString()
   }
}

