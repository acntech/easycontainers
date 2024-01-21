package no.acntech.easycontainers

import no.acntech.easycontainers.docker.DockerContainer
import no.acntech.easycontainers.k8s.K8sContainer
import no.acntech.easycontainers.output.LineCallback
import no.acntech.easycontainers.util.io.FileUtils
import no.acntech.easycontainers.util.text.NEW_LINE
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Builder for building a container.
 */
class ContainerBuilder {

   data class ConfigFile(
      val mountPath: String,
      val content: String,
   )

   data class Volume(
      val name: String,
      val mountPath: String,
   )

   companion object {
      private val log: Logger = LoggerFactory.getLogger(ContainerBuilder::class.java)
   }

   var containerType: ContainerType = ContainerType.KUBERNETES
   var name: String = UUID.randomUUID().toString()
   var namespace: String = "default"
   var labels: MutableMap<String, String> = mutableMapOf()
   val env: MutableMap<String, String> = mutableMapOf()
   var command: String? = null
   var args: List<String> = emptyList()
   var image: String? = null
   val exposedPorts: MutableMap<String, Int> = mutableMapOf()
   val portMappings: MutableMap<Int, Int> = mutableMapOf()
   val configFiles: MutableMap<String, ConfigFile> = mutableMapOf() // name -> ConfigMapVolume
   val volumes: MutableMap<String, Volume> = mutableMapOf() // name -> Volume
   var cpuRequest: CPU? = null
   var cpuLimit: CPU? = null
   var memoryRequest: Memory? = null
   var memoryLimit: Memory? = null
   var isEphemeral: Boolean = true
   var lineCallback: LineCallback = LineCallback { _ -> }
   var maxLifeTime: Duration? = null

   fun withType(type: ContainerType): ContainerBuilder {
      this.containerType = type
      return this
   }

   fun withName(name: String): ContainerBuilder {
      this.name = name
      return this
   }

   fun withNamespace(namespace: String): ContainerBuilder {
      this.namespace = namespace
      return this
   }

   fun withLabel(key: String, value: String): ContainerBuilder {
      labels[key] = value
      return this
   }

   fun withEnv(key: String, value: String): ContainerBuilder {
      env[key] = value
      return this
   }

   fun withEnv(env: Map<String, String>): ContainerBuilder {
      this.env.putAll(env)
      return this
   }

   fun withCommand(command: String): ContainerBuilder {
      this.command = command
      return this
   }

   fun withArgs(args: List<String>): ContainerBuilder {
      this.args = args
      return this
   }

   fun withImage(image: String): ContainerBuilder {
      this.image = image
      return this
   }

   fun withExposedPort(name: String, port: Int): ContainerBuilder {
      require(port in 1..65535) { "Port [$port] must be in the range 1..65535" }
      exposedPorts[name] = port
      return this
   }

   fun withPortMapping(port: Int, mappedPort: Int): ContainerBuilder {
      require(port in exposedPorts.values) { "Port [$port] cannot be mapped if not exposed" }
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

   fun withConfigFile(name: String, path: String, data: Map<String, String>, keyValSeparator: String = ": ") {
      require(FileUtils.isCompleteUnixPath(path)) { "Path [$path] is not a complete unix path" }
      val content = data.entries.joinToString(NEW_LINE) { (key, value) -> "$key$keyValSeparator$value" }
      configFiles[name] = ConfigFile(path, content)
   }

   fun withConfigFile(name: String, path: String, content: String) {
      require(FileUtils.isCompleteUnixPath(path)) { "Path [$path] is not a complete unix path" }
      configFiles[name] = ConfigFile(path, content)
   }

   fun withVolume(name: String, mountPath: String): ContainerBuilder {
      require(FileUtils.isCompleteUnixPath(mountPath)) { "Path [$mountPath] is not a complete unix path" }
      volumes[name] = Volume(name, mountPath)
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

   /**
    * Build the container.
    */
   fun build(): Container {

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

