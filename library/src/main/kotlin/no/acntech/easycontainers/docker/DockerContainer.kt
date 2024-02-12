package no.acntech.easycontainers.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.CreateContainerCmd
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.command.WaitContainerResultCallback
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.model.*
import no.acntech.easycontainers.AbstractContainer
import no.acntech.easycontainers.ContainerBuilder
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.docker.DockerConstants.DEFAULT_BRIDGE_NETWORK
import no.acntech.easycontainers.model.Container
import no.acntech.easycontainers.model.Host
import no.acntech.easycontainers.model.NetworkName
import no.acntech.easycontainers.util.platform.PlatformUtils.convertToDockerPath
import no.acntech.easycontainers.util.time.DurationFormatter
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference


internal class DockerContainer(
   containerBuilder: ContainerBuilder,
) : AbstractContainer(containerBuilder) {

   private inner class LogWatcher : Runnable {
      override fun run() {
         logOutputToCompletion()
      }

      private fun logOutputToCompletion() {
         dockerClient.logContainerCmd(containerId.get())
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .withTailAll()
            .exec(object : ResultCallback.Adapter<Frame>() {

               override fun onNext(item: Frame) {
                  val line = item.payload.decodeToString()
                  log.trace("Container [${getName()}] output: $line")
                  builder.outputLineCallback.onLine(line)
               }

               override fun onError(throwable: Throwable) {
                  log.warn("Container [${getName()}] output error", throwable)
               }

               override fun onComplete() {
                  log.info("Container [${getName()}] output complete")
               }

            }).awaitCompletion()
      }

   }

   companion object {
      var SCHEDULER: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
   }

   private val dockerClient: DockerClient = DockerClientFactory.createDefaultClient()

   private val containerId: AtomicReference<String> = AtomicReference()

   private val exitCode: AtomicInteger = AtomicInteger()

   private var ipAddress: InetAddress? = null

   private var host: Host? = null

   private var networkName: NetworkName? = null

   private var startedAt: Instant? = null

   private var finishedAt: Instant? = null

   private val executorService = Executors.newVirtualThreadPerTaskExecutor()

   override fun run() {
      changeState(Container.State.STARTED)
      pullImage()
      startContainer()
      executorService.submit(LogWatcher())
      extractStartTime()
   }

   override fun waitForCompletion(timeoutValue: Long, timeoutUnit: TimeUnit): Int {
      log.info("Waiting for container to complete: '${getName()}' ($containerId)")
      val callback = dockerClient.waitContainerCmd(containerId.get()).exec(WaitContainerResultCallback())
      val statusCode = callback.awaitStatusCode(timeoutValue, timeoutUnit)
      exitCode.set(statusCode)
      log.info(
         "Container '${getName()}' ($containerId) has completed in " +
            "${DurationFormatter.formatAsMinutesAndSecondsLong(getDuration()!!)} with status code $statusCode"
      )
      return statusCode
   }

   override fun stop() {
      requireState(Container.State.RUNNING)

      val callback = object : WaitContainerResultCallback() {

         override fun onComplete() {
            super.onComplete()
            log.debug("Callback: Container '${getName()} ($containerId)' has stopped.")
         }
      }

      try {
         dockerClient.stopContainerCmd(containerId.get()).exec()
         dockerClient.waitContainerCmd(containerId.get()).exec(callback).awaitCompletion().also {
            log.info("Container successfully stopped: $containerId")
         }

         changeState(Container.State.STOPPED)

      } catch (e: Exception) {
         log.warn("Failed to stop container: $containerId", e)
//         handleError(e)
      }
   }

   override fun kill() {
      requireState(Container.State.RUNNING)

      val callback = object : WaitContainerResultCallback() {

         override fun onComplete() {
            super.onComplete()
            log.debug("Callback: Container '${getName()} ($containerId)' has been terminated.")
         }
      }

      try {
         dockerClient.killContainerCmd(containerId.get()).exec()
         dockerClient.waitContainerCmd(containerId.get()).exec(callback).awaitCompletion().also {
            log.info("Container successfully terminated: ${getName()} ($containerId)")
         }

         changeState(Container.State.STOPPED)

      } catch (e: Exception) { // Swallow exception
         log.warn("Failed to kill container: $containerId", e)
//         handleError(e)
      }
   }

   override fun remove() {
      if (getState() == Container.State.RUNNING) {
         kill()
      }

      if (isEphemeral()) {
         log.debug("Container is ephemeral - already removed")
         cleanUpResources()
         changeState(Container.State.REMOVED)

      } else {
         val callback = object : WaitContainerResultCallback() {

            override fun onComplete() {
               super.onComplete()
               log.debug("Callback: Container '${getName()} ($containerId)' has been removed.")
            }
         }

         try {
            dockerClient.removeContainerCmd(containerId.get())
               .withForce(true)
               .exec()

            dockerClient.waitContainerCmd(containerId.get()).exec(callback).awaitCompletion().also {
               log.info("Container successfully removed: ${getName()} ($containerId)")
            }

         } catch (e: Exception) {
            log.debug("Failed to remove container: $containerId", e)

            when (e) {
               is DockerException, is DockerClientException -> {
                  changeState(Container.State.REMOVED)
               }

               else -> {
                  log.error("An error '{}' occurred removing container '${getName()}', re-throwing", e.message, e)
                  throw e;
               }
            }
         } finally {
            cleanUpResources()
         }
      }
   }

   override fun getIpAddress(): InetAddress? {
      return ipAddress
   }

   override fun getHost(): Host? {
      return host
   }

   override fun getDuration(): Duration? {
      return startedAt?.let { start ->
         if (finishedAt == null) {
            extractFinishTime()
         }
         val end = finishedAt ?: Instant.now()
         Duration.between(start, end)
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
         val hostConfig = prepareHostConfig()
         configureNetwork(hostConfig)
         val containerCmd = createContainerCommand(image, hostConfig)
         containerId.set(containerCmd.exec().id)

         startContainer(containerId.get())

         val containerInfo = inspectContainer(containerId.get())
         val ipAddress = getContainerIpAddress(containerInfo)

         setContainerHostName(containerInfo)
         scheduleContainerKillTask(builder.maxLifeTime)

         changeState(Container.State.RUNNING)

         log.info("Container '${getName()}' ('$containerId') is RUNNING with IP-address '$ipAddress' and hostname '$host'")

      } catch (e: Exception) {
         handleError(e)
      }
   }

   private fun prepareHostConfig(): HostConfig {
      return HostConfig.newHostConfig().apply {
         if (builder.isEphemeral) {
            log.info("Setting autoRemove to true")
            withAutoRemove(true)
         }
      }
   }

   private fun createContainerCommand(image: String, hostConfig: HostConfig): CreateContainerCmd {
      return dockerClient.createContainerCmd(image).apply {
         withName(builder.name.unwrap())
         withEnv(builder.env.toMap().entries.map { "${it.key.unwrap()}=${it.value.unwrap()}" })
         withLabels(builder.labels.map { (key, value) -> key.unwrap() to value.unwrap() }.toMap())
         withExposedPorts(builder.exposedPorts.values.map { ExposedPort.tcp(it.value) })
         configurePortBindings(this, hostConfig)
         configureVolumes(this, hostConfig)
         configureCommandAndArgs(this)
         withHostConfig(hostConfig)
      }
   }

   private fun configurePortBindings(cmd: CreateContainerCmd, hostConfig: HostConfig) {
      if (builder.portMappings.isNotEmpty()) {
         val portBindings = getPortBindings()
         hostConfig.withPortBindings(portBindings)
      } else {
         log.info("No ports to bind")
      }
   }

   private fun configureVolumes(cmd: CreateContainerCmd, hostConfig: HostConfig) {
      if (builder.volumes.isNotEmpty()) {
         val volumes = createDockerVolumes(hostConfig)
         cmd.withVolumes(*volumes.toTypedArray())
      } else {
         log.info("No volumes to bind")
      }
   }

   private fun configureCommandAndArgs(cmd: CreateContainerCmd) {
      val commandParts = mutableListOf<String>().apply {
         builder.command?.let { addAll(it.value.split("\\s".toRegex())) }
         builder.args?.let { addAll(it.toStringList()) }
      }
      cmd.withCmd(commandParts)
      log.info("Running container using command: $commandParts")
   }

   private fun startContainer(containerId: String) {
      log.info("Starting container: ${getName()} ($containerId)")
      dockerClient.startContainerCmd(containerId).exec()
   }

   private fun inspectContainer(containerId: String): InspectContainerResponse =
      dockerClient.inspectContainerCmd(containerId).exec()

   private fun getContainerIpAddress(containerInfo: InspectContainerResponse): InetAddress? {
      val ipAddressVal = containerInfo.networkSettings.networks[DEFAULT_BRIDGE_NETWORK]?.ipAddress
      return ipAddressVal?.let { InetAddress.getByName(it) }
   }

   private fun setContainerHostName(containerInfo: InspectContainerResponse) {
      containerInfo.config.hostName?.let {
         host = Host.of(it)
      }
   }

   private fun scheduleContainerKillTask(maxLifeTime: Duration?) {
      maxLifeTime?.let {
         SCHEDULER.schedule(KillTask(), it.toSeconds(), TimeUnit.SECONDS)
      }
   }

   private fun logContainerRunningInfo(containerId: String?, ipAddress: InetAddress?, host: Host?) {
      log.info("Container started: $containerId with IP address: $ipAddress and host: $host")
   }

   private fun configureNetwork(hostConfig: HostConfig) {
      builder.networkName?.let { networkName ->
         val networkMode = networkName.value.also {
            log.info("Using network-mode: $it")
         }

         // check networkMode is one of "bridge", "host", "none", "container:<name>", or a custom network name
         when (networkMode) {
            "bridge", "host", "none" -> {
               hostConfig.withNetworkMode(networkMode)
            }

            else -> {
               if (networkMode.startsWith("container:")) {
                  val containerName = networkMode.substringAfter("container:")
                  val container = dockerClient.listContainersCmd().withNameFilter(listOf(containerName)).exec().firstOrNull()
                  val containerId = container?.id ?: throw ContainerException("Container '$containerName' not found")
                  hostConfig.withNetworkMode("container:$containerId")

               } else {
                  // Custom network name (create if it doesn't exist
                  val networkList = dockerClient.listNetworksCmd().withNameFilter(networkMode).exec()
                  val networkId = if (networkList.isEmpty()) {
                     this.networkName = networkName // Must be removed if the container is ephimeral
                     // Network doesn't exist, so create it
                     dockerClient.createNetworkCmd()
                        .withName(networkMode)
                        .withDriver("bridge")
                        .exec().id.also {
                           log.info("Created network '$networkMode' with ID '$it'")
                        }
                  } else {
                     // Network already exists
                     networkList[0].id
                  }
                  hostConfig.withNetworkMode(networkId)
               }
            }
         }
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

         // Volume ninds
         .withBinds(*binds.toTypedArray())

         // Tmpfs mounts
         .apply {
            if (tmpfsMounts.isNotEmpty()) {
               log.info("Setting tmpfs mounts: $tmpfsMounts")
               withMounts(tmpfsMounts)
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

   private fun getExistingVolumeNames(): Set<String> {
      val volumesResponse = dockerClient.listVolumesCmd().exec()
      return volumesResponse.volumes.map { it.name }.toSet()
   }

   private fun extractStartTime() {
      val response = dockerClient.inspectContainerCmd(containerId.get()).exec()
      response.state.startedAt?.let {
         startedAt = Instant.parse(it)
      }
   }

   private fun extractFinishTime() {
      val response = dockerClient.inspectContainerCmd(containerId.get()).exec()
      response.state.finishedAt.let {
         finishedAt = Instant.parse(it)
      }
   }

   private fun cleanUpResources() {
      networkName?.let {
         log.info("Removing network '${it.value}'")
         try {
            dockerClient.removeNetworkCmd(it.value).exec()
         } catch (e: Exception) {
            log.error("Failed to remove network '${it.value}'", e)
         }
      }
   }

   private fun handleError(e: Exception, swallow: Boolean = false) {
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