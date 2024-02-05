package no.acntech.easycontainers.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.model.*
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import no.acntech.easycontainers.AbstractContainer
import no.acntech.easycontainers.ContainerBuilder
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.model.Container
import no.acntech.easycontainers.util.platform.PlatformUtils.convertToDockerPath
import java.nio.file.Path

internal class DockerContainer(
   containerBuilder: ContainerBuilder,
) : AbstractContainer(containerBuilder) {

   private val dockerClient: DockerClient

   private var containerId: String? = null

   init {
      val configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()

      val dockerHost = System.getenv(DockerConstants.ENV_DOCKER_HOST)
      if (dockerHost != null && dockerHost.isNotEmpty()) {
         log.info("Using Docker host from environment variable: {}", dockerHost)
         configBuilder.withDockerHost(dockerHost)
      }
      dockerClient = DockerClientBuilder.getInstance(configBuilder.build()).build()
   }

   override fun run() {
      changeState(Container.State.STARTED)
      pullImage()
      startContainer()
      logOutputToCompletion() // Blocking
   }

   override fun stop() {
      try {
         dockerClient.stopContainerCmd(containerId!!).exec()
         log.info("Container successfully stopped: $containerId")
         changeState(Container.State.STOPPED)

      } catch (e: Exception) {
         handleError(e)
      }
   }

   override fun kill() {
      try {
         dockerClient.killContainerCmd(containerId!!).exec()
         log.info("Container successfully killed: $containerId")
         changeState(Container.State.STOPPED)

      } catch (e: Exception) {
         handleError(e)
      }
   }

   override fun remove() {
      try {
         dockerClient.removeContainerCmd(containerId!!)
            .withForce(true)
            .exec()
         log.info("Container successfully removed: $containerId")
         changeState(Container.State.REMOVED)

      } catch (e: Exception) {
         handleError(e)
      }
   }

   private fun pullImage() {
      try {
         dockerClient.pullImageCmd(builder.image.toFQDN())
            .exec(object : PullImageResultCallback() {

               override fun onNext(item: PullResponseItem?) {
                  log.info("Pulling image: ${item?.status}")
                  super.onNext(item)
               }

            }).awaitCompletion()

         log.info("Image pulled successfully: ${builder.image}")

      } catch (e: Exception) {
         handleError(e)
      }
   }

   private fun startContainer() {
      try {
         val image = builder.image.toFQDN()
         val hostConfig = HostConfig.newHostConfig()

         containerId = dockerClient.createContainerCmd(image)
            // Name of the container
            .withName(builder.name.unwrap())

            // Environment variables
            .withEnv(builder.env.toMap().map { "${it.key.unwrap()}=${it.value.unwrap()}" })

            // Labels
            .withLabels(builder.labels.map { (key, value) -> key.unwrap() to value.unwrap() }.toMap())

            // Port bindings
            .apply {
               if (builder.portMappings.isEmpty()) {
                  log.info("No ports to bind")
               } else {
                  val portBindings = this@DockerContainer.getPortBindings()
                  hostConfig.withPortBindings(portBindings)
               }
            }

            // Exposed ports (from the container)
            .withExposedPorts(builder.exposedPorts.values.map { ExposedPort.tcp(it.value) })

            // Volumes
            .apply {
               if (builder.volumes.isEmpty()) {
                  log.info("No volumes to bind")
               } else {
                  val volumes = createDockerVolumes(hostConfig)
                  withVolumes(*volumes.toTypedArray())
               }
            }

            // Apply the hostConfig
            .withHostConfig(hostConfig)

            // Command and arguments
            .apply {
               val commandParts = mutableListOf<String>()
               builder.command?.let { commandParts.addAll(it.value.split("\\s".toRegex())) }
               builder.args?.let { commandParts.addAll(it.toStringList()) }
               withCmd(commandParts).also {
                  log.info("Running container using command: $commandParts")
               }
            }

            // Create the container
            .exec()

            // Get the container ID
            .id

         dockerClient.startContainerCmd(containerId!!).exec().also {
            log.info("Starting container: $containerId")
         }
         changeState(Container.State.RUNNING)
      } catch (e: Exception) {
         handleError(e)
      }
   }

   private fun createDockerVolumes(hostConfig: HostConfig): List<Volume> {
      val volumeMap = builder.volumes.associateWith { Volume(it.mountPath.value) }
      val dockerVolumeNames = getExistingVolumeNames()

      val binds = builder.volumes.filter { it.memoryBacked }
         .map { volume -> createBind(volume, volumeMap[volume], dockerVolumeNames) }

      val tmpfsMounts = builder.volumes.filter { it.memoryBacked }
         .map { volume -> createTmpfsMount(volume) }

      configureHostConfigVolumes(hostConfig, binds, tmpfsMounts)

      return volumeMap.values.toList()
   }

   private fun createBind(
      volume: no.acntech.easycontainers.model.Volume,
      dockerVolume: Volume?,
      dockerVolumeNames: Set<String>,
   ): Bind {
      val volumeName = volume.name.value
      return if (dockerVolumeNames.contains(volumeName)) {
         log.info("Using existing named Docker volume '$volumeName' with mount-path '${volume.mountPath}'")
         Bind(volumeName, dockerVolume)
      } else {
         log.info("Using hostDir '${volume.hostDir}' for volume '$volumeName'")
         val actualHostDir =
            getActualHostDir(volume.hostDir ?: throw ContainerException("Volume '$volumeName' must have a hostDir"))
         Bind(actualHostDir, dockerVolume)
      }
   }

   private fun createTmpfsMount(volume: no.acntech.easycontainers.model.Volume): Mount {
      val volumeName = volume.name.value
      val mountPath = volume.mountPath.value
      val memory = volume.memory
      log.info("Using memory-backed volume '$volumeName' with mount-path '$mountPath' and memory '$memory'")
      return Mount()
         .withType(MountType.TMPFS)
         .withTarget(mountPath)
         .apply { memory?.let { withTmpfsOptions(TmpfsOptions().withSizeBytes(memory.bytes.longValueExact())) } }
   }

   private fun configureHostConfigVolumes(hostConfig: HostConfig, binds: List<Bind>, tmpfsMounts: List<Mount>): HostConfig {
      return hostConfig
         .withBinds(*binds.toTypedArray())
         .apply {
            if (tmpfsMounts.isNotEmpty()) {
               log.info("Setting tmpfs mounts: $tmpfsMounts")
               withMounts(tmpfsMounts)
            }
         }
         .apply {
            if (builder.isEphemeral) {
               log.info("Setting autoRemove to true")
               withAutoRemove(true)
            }
         }
   }

   private fun getActualHostDir(path: Path): String {
      return convertToDockerPath(path)
   }

   private fun getPortBindings(): List<PortBinding> {
      return builder.portMappings.map { (containerPort, hostPort) ->
         val dockerExposedPort = ExposedPort.tcp(containerPort.value) // Exported port from the container
         val dockerHostPort = Ports.Binding.bindPort(hostPort.value) // The binding to the host
         PortBinding(dockerHostPort, dockerExposedPort)
      }
   }

   private fun logOutputToCompletion() {
      dockerClient.logContainerCmd(containerId!!)
         .withStdOut(true)
         .withStdErr(true)
         .withFollowStream(true)
         .withTailAll()
         .exec(object : ResultCallback.Adapter<Frame>() {

            override fun onNext(item: Frame) {
               val line = item.payload.decodeToString()
               log.trace("Container [${builder.name}] output: $line")
               builder.outputLineCallback.onLine(line)
            }

            override fun onError(throwable: Throwable) {
               log.warn("Container [${builder.name}] output error", throwable)
            }

            override fun onComplete() {
               log.info("Container [${builder.name}] output complete")
            }

         }).awaitCompletion()
   }

   private fun getExistingVolumeNames(): Set<String> {
      val volumesResponse = dockerClient.listVolumesCmd().exec()
      return volumesResponse.volumes.map { it.name }.toSet()
   }

   private fun handleError(e: Exception) {
      when (e) {
         is DockerException, is DockerClientException -> {
            log.error("A Docker error '{}' occurred in container '${builder.name}', raising ContainerException", e.message, e)
            changeState(Container.State.FAILED)
            throw ContainerException("Error '${e.message} in container ${builder.name}", e)
         }

         else -> {
            log.error("An error '{}' occurred in container '${builder.name}', re-throwing", e.message, e)
            changeState(Container.State.FAILED)
            throw e;
         }
      }
   }


}