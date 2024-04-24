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
import com.github.dockerjava.api.model.Volume
import com.google.common.io.CountingInputStream
import no.acntech.easycontainers.AbstractContainerRuntime
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.docker.DockerConstants.NETWORK_MODE_BRIDGE
import no.acntech.easycontainers.docker.DockerConstants.NETWORK_MODE_HOST
import no.acntech.easycontainers.docker.DockerConstants.NETWORK_MODE_NONE
import no.acntech.easycontainers.docker.DockerConstants.NETWORK_NODE_CONTAINER
import no.acntech.easycontainers.docker.DockerConstants.PROP_ENABLE_NATIVE_DOCKER_ENTRYPOINT_STRATEGY
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.model.Container
import no.acntech.easycontainers.util.io.FileUtils
import no.acntech.easycontainers.util.lang.asStringMap
import no.acntech.easycontainers.util.lang.guardedExecution
import no.acntech.easycontainers.util.platform.PlatformUtils
import no.acntech.easycontainers.util.text.*
import org.awaitility.Awaitility.await
import org.awaitility.core.ConditionTimeoutException
import java.io.*
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * DockerRuntime class provides functionality for managing Docker containers.
 * It allows starting, stopping, and deleting containers, executing commands inside containers,
 * transferring files to and from containers, and retrieving container information.
 *
 * @property dockerClient The Docker client instance used for communicating with the Docker daemon.
 * @property containerId The ID of the Docker container.
 * @property exitCode The exit code of the last executed command in the Docker container.
 * @property ipAddress The IP address of the Docker container.
 * @property host The host where the Docker container is running.
 * @property networkName The name of the network the Docker container is connected to.
 * @property startedAt The timestamp when the Docker container was started.
 * @property finishedAt The timestamp when the Docker container finished its execution.
 */
internal class DockerRuntime(
   container: GenericContainer,
   private val dockerClient: DockerClient = DockerClientFactory.createDefaultClient(),
) : AbstractContainerRuntime(container) {

   private inner class EventSubscriber : Runnable {
      override fun run() {

         val callback = object : ResultCallback.Adapter<Frame>() {

            override fun onNext(item: Frame) {
               val line = item.payload.decodeToString()
               log.trace("Container '${getDisplayName()}' output: $line")
               container.getOutputLineCallback().onLine(line)
            }

            override fun onError(throwable: Throwable) {
               log.warn("Container '${getDisplayName()}' output error", throwable)
               container.changeState(Container.State.FAILED)
            }

            override fun onComplete() {
               try {
                  log.info("Container '${getDisplayName()}' output complete")

                  guardedExecution(
                     block = {
                        val containerInfo = dockerClient.inspectContainerCmd(containerId.get()).exec()
                        setFinishedTime(containerInfo)
                        setExitCode(containerInfo)
                        log.info("Container '${getDisplayName()}' finished at $finishedAt with exit code: $exitCode")
                     }, onError = {
                        log.warn("Error '${it.message}' inspecting container '${getDisplayName()}': ${it.message}", it)
                        container.changeState(Container.State.FAILED)
                     }
                  )

               } finally {
                  container.changeState(Container.State.STOPPED)

                  if (container.isEphemeral()) {
                     guardedExecution({ cleanUpResources() })
                     container.changeState(Container.State.DELETED)
                  }
               }
            }

         }

         dockerClient.logContainerCmd(containerId.get())
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .withTailAll()
            .exec(callback)
            .awaitCompletion()
      }
   }

   private val containerId: AtomicReference<String> = AtomicReference()

   private var _exitCode: AtomicReference<Int> = AtomicReference()
   private var exitCode: Int?
      get() = _exitCode.get()
      set(value) {
         if (value != null) {
            _exitCode.compareAndSet(null, value)
         }
      }

   private var _finishedAt: AtomicReference<Instant> = AtomicReference()
   private var finishedAt: Instant?
      get() = _finishedAt.get()
      set(value) {
         if (value != null) {
            _finishedAt.compareAndSet(null, value)
         }
      }

   private var ipAddress: InetAddress? = null

   private var host: Host? = null

   private var networkName: NetworkName? = null

   private var startedAt: Instant? = null

   init {
      log.debug("DockerRuntime using container builder:$NEW_LINE${container.builder}")
   }

   override fun getType(): ContainerPlatformType {
      return ContainerPlatformType.DOCKER
   }

   /**
    * Retrieves the container ID of the Docker container if it has been started, otherwise the container name.
    */
   override fun getName(): ContainerName {
      return containerId.get()?.run { ContainerName.of(this) } ?: container.getName()
   }

   override fun start() {
      container.changeState(Container.State.INITIALIZING, Container.State.UNINITIATED)
      pullImage()
      createAndStartContainer()
      GENERAL_EXECUTOR_SERVICE.submit(EventSubscriber())
      super.start()
      container.changeState(Container.State.RUNNING, Container.State.INITIALIZING)
   }

   override fun stop() {
      if (container.getState() == Container.State.STOPPED ||
         container.getState() == Container.State.TERMINATING ||
         container.getState() == Container.State.DELETED
      ) {
         log.debug("Container is already stopped: ${getDisplayName()}")
         return
      }

      container.changeState(Container.State.TERMINATING, Container.State.RUNNING)

      val callback = object : WaitContainerResultCallback() {

         override fun onComplete() {
            super.onComplete()
            log.debug("Callback: Container '${getDisplayName()}' has been stopped.")
         }
      }

      guardedExecution(
         {
            dockerClient.stopContainerCmd(containerId.get()).exec()
            dockerClient.waitContainerCmd(containerId.get()).exec(callback).awaitCompletion().also {
               log.info("Container successfully stopped: ${getDisplayName()}")
            }
            finishUp()
         },
         listOf(DockerException::class, DockerClientException::class),
         {
            val msg = "Error '${it.message} stopping (Docker) container: ${getDisplayName()}"
            log.warn(msg)
            throw ContainerException(msg, it)
         },
         finallyBlock = {
            super.stop()
            container.changeState(Container.State.STOPPED)
         }
      )
   }

   override fun kill() {
      container.changeState(Container.State.TERMINATING, Container.State.RUNNING)

      val callback = object : WaitContainerResultCallback() {

         override fun onComplete() {
            super.onComplete()
            log.debug("Callback: Container '${getDisplayName()}' has been killed.")
         }

      }

      guardedExecution(
         {
            dockerClient.killContainerCmd(containerId.get()).exec()
            dockerClient.waitContainerCmd(containerId.get()).exec(callback).awaitCompletion().also {
               log.info("Container successfully killed: ${getDisplayName()}")
            }
            finishUp()
         },
         listOf(DockerException::class, DockerClientException::class),
         {
            val msg = "Error '${it.message} killing (Docker) container: ${getDisplayName()}"
            log.warn(msg)
            container.changeState(Container.State.FAILED)
            throw ContainerException(msg, it)
         }
      )

      container.changeState(Container.State.STOPPED)
   }

   override fun delete(force: Boolean) {
      if (container.getState() == Container.State.DELETED) {
         log.debug("Container is already deleted: ${getDisplayName()}")
         return
      }

      super.delete(force)

      if (container.getState() == Container.State.RUNNING) {
         kill()
      }

      if (!force) {
         container.requireOneOfStates(Container.State.STOPPED, Container.State.FAILED)
      }

      if (container.isEphemeral()) {
         log.debug("Container is ephemeral, hence already removed: ${getDisplayName()}")
         cleanUpResources()
         container.changeState(Container.State.DELETED)

      } else {
         log.info("Removing Docker container: ${getDisplayName()}")

         guardedExecution(
            {
               dockerClient.removeContainerCmd(containerId.get())
//                  .withRemoveVolumes(true)
                  .withForce(true)
                  .exec()
            },
            listOf(DockerException::class, DockerClientException::class),
            {
               val msg = "Error '${it.message}' deleting container: ${getDisplayName()}"
               log.warn(msg)
               throw ContainerException(msg, it)
            },
            finallyBlock = { cleanUpResources() }
         )

         container.changeState(Container.State.DELETED)
      }
   }

   override fun execute(
      executable: Executable,
      args: Args?,
      useTty: Boolean,
      workingDir: UnixDir?,
      input: InputStream?,
      output: OutputStream,
      waitTimeValue: Long?,
      waitTimeUnit: TimeUnit?,
   ): Pair<Int?, String?> {
      return internalExecute(
         listOf(executable.value) + (args?.toStringList() ?: emptyList()),
         workingDir?.value,
         useTty,
         input,
         output,
         waitTimeValue,
         waitTimeUnit,
      )
   }

   override fun putFile(localFile: Path, remoteDir: UnixDir, remoteFilename: String?): Long {
      require(localFile.exists()) { "Local file '$localFile' does not exist" }
      require(localFile.isRegularFile()) { "Local path '$localFile' is not a file" }

      container.requireOneOfStates(Container.State.RUNNING)

      // Check if remoteDir exists, if not create it
      createContainerDirIfNotExists(remoteDir)

      val tarFile = FileUtils.tarFile(localFile, remoteFilename ?: localFile.fileName.toString())
      val inputStream = FileInputStream(tarFile)

      guardedExecution(
         {
            dockerClient.copyArchiveToContainerCmd(containerId.get())
               .withRemotePath(remoteDir.value)
               .withTarInputStream(inputStream)
               .exec()

            log.info("File '${localFile.fileName}' uploaded to container '${getDisplayName()}' in directory '${remoteDir.value}'")
         },
         listOf(DockerException::class, DockerClientException::class),
         {
            val msg = "Error '${it.message}' putting file: ${localFile.fileName} to container: ${getDisplayName()}"
            log.warn(msg)
            throw ContainerException(msg, it)
         },
         finallyBlock = {
            guardedExecution({ inputStream.close() })
            guardedExecution({ tarFile.delete() })
         }
      )
      return localFile.toFile().length()
   }

   override fun getFile(remoteDir: UnixDir, remoteFilename: String, localPath: Path?): Path {
      val tempTarFile = File.createTempFile("temp", ".tar")

      val remoteFilePath = "$remoteDir/$remoteFilename"

      guardedExecution(
         {
            dockerClient
               .copyArchiveFromContainerCmd(containerId.get(), remoteFilePath)
               .exec()
               .use { responseStream ->
                  FileOutputStream(tempTarFile).use { outputStream ->
                     responseStream.transferTo(outputStream)
                  }
               }

            val targetPath = determineLocalPath(localPath, remoteFilename)

            // Untar the tempTarFile to the targetPath
            FileUtils.untarFile(tempTarFile, targetPath)

            return targetPath.also {
               log.info("File '$remoteDir/$remoteFilename' successfully downloaded to $it")
            }
         },
         listOf(DockerException::class, DockerClientException::class),
         {
            val msg = "Error '${it.message}' getting file '$remoteDir/$remoteFilename' from container' ${getDisplayName()}'"
            log.warn(msg)
            throw ContainerException(msg, it)
         },
         finallyBlock = { guardedExecution({ tempTarFile.delete() }) }
      )

      return Paths.get(EMPTY_STRING) // This is never reached, but the compiler doesn't know that
   }

   override fun putDirectory(localDir: Path, remoteDir: UnixDir): Long {
      require(localDir.exists() && localDir.isDirectory()) { "Local directory '$localDir' does not exist" }
      container.requireOneOfStates(Container.State.RUNNING)

      createContainerDirIfNotExists(remoteDir)

      val tarInput = CountingInputStream(FileUtils.tar(localDir, includeParentDir = true))

      guardedExecution(
         {
            dockerClient.copyArchiveToContainerCmd(containerId.get())
               .withRemotePath(remoteDir.value)
               .withTarInputStream(tarInput)
               .exec()
            log.info("Directory'$localDir' uploaded to container '${getDisplayName()} in directory '${remoteDir.value}'")
         },
         listOf(DockerException::class, DockerClientException::class),
         {
            val msg = "Error '${it.message}' putting directory: $localDir to container: ${getDisplayName()}"
            log.warn(msg)
            throw ContainerException(msg, it)
         })

      return tarInput.count
   }

   override fun getDirectory(remoteDir: UnixDir, localDir: Path): Pair<Path, List<Path>> {
      require(localDir.isDirectory()) { "Local directory '$localDir' does not exist" }

      guardedExecution(
         {
            val tarInput: InputStream = dockerClient
               .copyArchiveFromContainerCmd(containerId.get(), remoteDir.value)
               .exec()

            return FileUtils.untar(tarInput, localDir).also {
               log.info("Directory '$remoteDir' downloaded to '$localDir' with files: ${it.second}")
            }
         },
         listOf(DockerException::class, DockerClientException::class),
         {
            val msg = "Error '${it.message}' getting directory '$remoteDir' from container' ${getDisplayName()}'"
            log.warn(msg)
            throw ContainerException(msg, it)
         }
      )

      return Paths.get(EMPTY_STRING) to emptyList() // This is never reached, but the compiler doesn't know that
   }

   override fun getDuration(): Duration? {
      return this.startedAt?.let { start ->
         if (this.finishedAt == null) {
            setFinishedTime(inspectContainer())
         }
         val end = this.finishedAt ?: Instant.now()
         Duration.between(start, end)
      }
   }

   override fun getExitCode(): Int? {
      if (_exitCode.get() == null && containerId.get() != null && !container.isEphemeral()) {
         val containerInfo = dockerClient.inspectContainerCmd(containerId.get()).exec()
         setExitCode(containerInfo)
      }
      return exitCode
   }

   override fun getHost(): Host? {
      return host
   }

   override fun getIpAddress(): InetAddress? {
      return ipAddress
   }

   private fun pullImage() {
      guardedExecution({
         dockerClient.pullImageCmd(container.getImage().toFQDN())
            .exec(object : PullImageResultCallback() {

               override fun onNext(item: PullResponseItem?) {
                  log.info("Pulling image: ${item?.status}")
                  super.onNext(item)
               }

            }).awaitCompletion()

         log.info("Image pulled successfully: ${container.getImage()}")

      }, listOf(DockerException::class, DockerClientException::class), {
         val msg = "Error '${it.message}' pulling image: ${container.getImage()}"
         log.warn(msg)
         throw ContainerException(msg, it)
      })
   }

   private fun createAndStartContainer() {
      guardedExecution(
         {
            val image = container.getImage().toFQDN()
            val hostConfig = prepareHostConfig()

            configureNetwork(hostConfig)

            val containerCmd = createContainerCommand(image, hostConfig).also {
               log.debug("containerCmd created:$NEW_LINE${it.asStringMap()}")
            }

            containerCmd.exec().id.also {
               log.debug("Container created: ${getDisplayName()}")
               containerId.set(it)
            }

            startContainer()

            val containerInfo = inspectContainer()

            // Set container properties
            setContainerIpAddress(containerInfo)
            setStartTime(containerInfo)
            setContainerHostName(containerInfo)

         },
         listOf(DockerException::class, DockerClientException::class, ConditionTimeoutException::class),
         {
            val msg = "Error '${it}' creating/starting Docker container: ${getDisplayName()}"
            log.warn(msg)
            container.changeState(Container.State.FAILED)
            throw ContainerException(msg, it)
         }
      )
   }

   private fun startContainer() {
      log.info("Starting Docker container: ${getDisplayName()}")
      dockerClient.startContainerCmd(containerId.get()).exec()

      log.debug("Waiting for the Docker container to reach the RUNNING state: ${getDisplayName()}")
      await()
         .atMost(60, TimeUnit.SECONDS)
         .pollInterval(1, TimeUnit.SECONDS)
         .until {
            val inspection = inspectContainer()
            val dockerStateVal = inspection.state.status.also {
               log.debug("Docker container state.status: $it")
            }

            if (dockerStateVal == null) {
               false
            } else {
               val dockerState = DockerContainerState.valueOf(dockerStateVal.uppercase())

               when (dockerState) {
                  DockerContainerState.RUNNING -> true
                  DockerContainerState.CREATED, DockerContainerState.RESTARTING, DockerContainerState.PAUSED -> false
                  DockerContainerState.EXITED -> throw ContainerException("Container '${getDisplayName()}' has exited")
                  DockerContainerState.DEAD -> throw ContainerException("Container '${getDisplayName()}' is dead")
                  DockerContainerState.REMOVING -> throw ContainerException("Container '${getDisplayName()}' is being removed")
               }
            }
         }
   }

   private fun inspectContainer(): InspectContainerResponse =
      dockerClient.inspectContainerCmd(containerId.get()).exec().also {
         log.debug("Container inspected: ${getDisplayName()}: $it")
      }

   private fun prepareHostConfig(): HostConfig {
      return HostConfig.newHostConfig().apply {
         withAutoRemove(container.isEphemeral()).also {
            log.info("Using auto-remove: ${container.isEphemeral()}")
         }
      }
   }

   private fun finishUp() {
      if (!container.isEphemeral()) {
         val info = inspectContainer()
         setFinishedTime(info)
         setExitCode(info)
      } else {
         finishedAt = Instant.now()
      }
   }

   private fun determineLocalPath(localPath: Path?, remoteFilename: String): Path = when {
      localPath == null -> Files.createTempDirectory("docker-file-transfer-").resolve(remoteFilename)
      Files.isDirectory(localPath) -> localPath.resolve(remoteFilename)
      else -> localPath
   }

   private fun getActualHostDir(path: Path): String {
      return PlatformUtils.convertToDockerPath(path)
   }

   private fun getExistingVolumeNames(): Set<String> {
      val volumesResponse = dockerClient.listVolumesCmd().exec()
      return volumesResponse.volumes.map { it.name }.toSet()
   }

   private fun getDockerPortBindings(): List<PortBinding> {
      return container.getPortMappings().map { (containerPort, hostPort) ->
         val dockerExposedPort = ExposedPort.tcp(containerPort.value) // Exported port from the container
         val dockerHostPort = Ports.Binding.bindPort(hostPort.value) // The binding to the host
         PortBinding(dockerHostPort, dockerExposedPort)
      }
   }

   private fun setContainerIpAddress(containerInfo: InspectContainerResponse) {
      val ipAddressVal = containerInfo.networkSettings.networks[DockerConstants.DEFAULT_BRIDGE_NETWORK]?.ipAddress
      ipAddressVal?.let { ipAddress = InetAddress.getByName(it) }
   }

   private fun setContainerHostName(containerInfo: InspectContainerResponse) {
      containerInfo.config.hostName?.let {
         host = Host.of(it)
      }
   }

   private fun setStartTime(containerInfo: InspectContainerResponse) {
      containerInfo.state.startedAt?.let {
         startedAt = Instant.parse(it)
      }
   }

   private fun setFinishedTime(containerInfo: InspectContainerResponse) {
      containerInfo.state.finishedAt?.let { time ->
         finishedAt = Instant.parse(time)
      }
   }

   private fun setExitCode(containerInfo: InspectContainerResponse) {
      exitCode = containerInfo.state.exitCodeLong?.toInt()
   }

   private fun createContainerCommand(image: String, hostConfig: HostConfig): CreateContainerCmd {
      return dockerClient.createContainerCmd(image).apply {
         withName(container.getName().unwrap())
         withEnv(container.getEnv().toMap().entries.map { "${it.key.unwrap()}=${it.value.unwrap()}" })
         withLabels(container.getLabels().map { (key, value) -> key.unwrap() to value.unwrap() }.toMap())
         withExposedPorts(container.getExposedPorts().map { ExposedPort.tcp(it.value) })
         configurePortBindings(hostConfig)
         configureVolumes(this, hostConfig)
         configureCommandAndArgs(this)
         withHostConfig(hostConfig)

         // We want to mimic the behaviour of k8s here, so we override the entrypoint if the command or args are set,
         // unless explicitly told not to with the enableNativeDockerEntrypointStrategy property
         if (container.getCommand() != null || (container.getArgs() != null && container.getArgs()!!.args.isNotEmpty())) {
            val property = container.builder.customProperties[PROP_ENABLE_NATIVE_DOCKER_ENTRYPOINT_STRATEGY]
            val enableNativeStrategy = property is Boolean && property ||
               property is String && property.equals("true", ignoreCase = true)
            if (!enableNativeStrategy) {
               withEntrypoint("/bin/sh", "-c")
            }
         }
      }
   }

   private fun createDockerVolumes(hostConfig: HostConfig): List<Volume> {
      val volumes = container.getVolumes()
      val volumeMap = volumes.associateWith {
         Volume(it.mountDir.value)
      }
      val dockerVolumeNames = getExistingVolumeNames()

      val volumeBinds = volumes
         .filter { !it.memoryBacked }
         .map { volume -> createBind(volume, volumeMap[volume], dockerVolumeNames) }

      val fileBinds = container.builder.containerFiles.map { (name, file) ->
         createContainerFileBind(file)
      }

      val tmpfsMounts = volumes
         .filter { it.memoryBacked }
         .map { volume -> createTmpfsMount(volume) }

      configureHostConfigVolumes(hostConfig, volumeBinds + fileBinds, tmpfsMounts)

      return volumeMap.values.toList()
   }

   private fun createContainerFileBind(containerFile: ContainerFile): Bind {
      val hostFile = containerFile.hostFile ?: Files.createTempFile(containerFile.name.value, null)

      // If content is not null, write content to this file
      containerFile.content?.let { content ->
         hostFile.toFile().writeText(content)
      }

      val actualBindPath = PlatformUtils.convertToDockerPath(hostFile)

      // Get the complete path including the filename as the mount point
      val mountPath = "${containerFile.mountPath}$FORWARD_SLASH${containerFile.name}"

      // Finally, create a Docker bind
      val bind = Bind(actualBindPath, Volume(mountPath))

      return bind.also {
         log.info(
            "Using host file '${actualBindPath}' for container file '${containerFile.name}'" +
               " with mount-path '$mountPath'"
         )
      }
   }

   private fun createBind(
      volume: no.acntech.easycontainers.model.Volume,
      dockerVolume: Volume?,
      dockerVolumeNames: Set<String>,
   ): Bind {
      val volumeName = volume.name.value

      return if (dockerVolumeNames.contains(volumeName)) {
         log.info("Using existing named Docker volume '$volumeName' with mount-path '${volume.mountDir}'")
         Bind(volumeName, dockerVolume)

      } else {
         log.info("Using hostDir '${volume.hostDir}' for volume '$volumeName'")

         volume.hostDir?.let { hostDir ->
            val actualHostDir = getActualHostDir(hostDir)
            Bind(actualHostDir, dockerVolume)
         } ?: throw ContainerException("Volume '$volumeName' must have a host-dir")
      }
   }

   private fun createTmpfsMount(volume: no.acntech.easycontainers.model.Volume): Mount {
      val volumeName = volume.name.value
      val mountPath = volume.mountDir.value
      val memory = volume.memory
      log.info("Using memory-backed volume '$volumeName' with mount-path '$mountPath' and memory '$memory'")
      return Mount()
         .withType(MountType.TMPFS)
         .withTarget(mountPath)
         .apply { memory?.let { withTmpfsOptions(TmpfsOptions().withSizeBytes(memory.bytes.longValueExact())) } }
   }

   private fun createContainerDirIfNotExists(remoteDir: UnixDir) = internalExecute(listOf("mkdir", "-p", remoteDir.value))

   private fun configureNetwork(hostConfig: HostConfig) {
      container.getNetworkName()?.let { networkName ->
         val networkModeType = networkName.value.also {
            log.info("Using network-mode: $it")
         }
         when (networkModeType) {
            NETWORK_MODE_BRIDGE, NETWORK_MODE_HOST, NETWORK_MODE_NONE -> {
               hostConfig.withNetworkMode(networkModeType)
            }

            else -> {
               configureCustomNetworkMode(networkModeType, hostConfig)
            }
         }
      }
   }

   private fun configureCustomNetworkMode(networkMode: String, hostConfig: HostConfig) {
      when {
         networkMode.startsWith(NETWORK_NODE_CONTAINER) -> {
            val containerId = getContainerIdByName(networkMode.substringAfter(NETWORK_NODE_CONTAINER))
            hostConfig.withNetworkMode("container:$containerId")
         }

         else -> {
            val networkId = determineNetworkId(networkMode)
            hostConfig.withNetworkMode(networkId)
         }
      }
   }

   private fun getContainerIdByName(containerName: String): String {
      val container = listContainersByName(containerName).firstOrNull()
      return container?.id ?: throw ContainerException("Container '$containerName' not found")
   }

   private fun listContainersByName(name: String) = dockerClient.listContainersCmd().withNameFilter(listOf(name)).exec()

   private fun determineNetworkId(networkMode: String): String {
      val networkList = dockerClient.listNetworksCmd().withNameFilter(networkMode).exec()
      return if (networkList.isEmpty()) {
         this.networkName = networkName // Must be removed if the container is ephimeral
         createNetworkWithMode(networkMode)
      } else {
         networkList[0].id
      }
   }

   private fun createNetworkWithMode(networkMode: String) =
      dockerClient.createNetworkCmd().withName(networkMode).withDriver("bridge").exec().id.also {
         log.info("Created network '$networkMode' with ID '$it'")
      }

   private fun configurePortBindings(hostConfig: HostConfig) {
      if (container.getPortMappings().isNotEmpty()) {
         val portBindings = getDockerPortBindings()
         hostConfig.withPortBindings(portBindings)
      } else {
         log.debug("No ports to bind for container: ${getDisplayName()}")
      }
   }

   private fun configureVolumes(cmd: CreateContainerCmd, hostConfig: HostConfig) {
      if (container.getVolumes().isNotEmpty() || container.builder.containerFiles.isNotEmpty()) {
         val volumes = createDockerVolumes(hostConfig)
         cmd.withVolumes(*volumes.toTypedArray())
      } else {
         log.debug("No volumes to bind")
      }
   }

   private fun configureHostConfigVolumes(hostConfig: HostConfig, binds: List<Bind>, tmpfsMounts: List<Mount>): HostConfig {
      return hostConfig

         // Volume binds
         .withBinds(*binds.toTypedArray())

         // Tmpfs mounts
         .apply {
            if (tmpfsMounts.isNotEmpty()) {
               log.info("Setting tmpfs mounts: $tmpfsMounts")
               withMounts(tmpfsMounts)
            }
         }
   }

   private fun configureCommandAndArgs(cmd: CreateContainerCmd) {
      val commandParts = mutableListOf<String>().apply {
         container.getCommand()?.let { addAll(it.value.splitOnWhites()) }
         container.getArgs()?.let { addAll(it.toStringList()) }
      }
      cmd.withCmd(commandParts)
      log.info("Using container command: $commandParts")
   }

   private fun cleanUpResources() {
      networkName?.let { networkName ->
         log.info("Removing network '${networkName.value}'")
         guardedExecution(
            { dockerClient.removeNetworkCmd(networkName.value).exec() },
            listOf(DockerException::class, DockerClientException::class),
            {
               val msg = "Error '${it.message}' removing network from: ${getDisplayName()}"
               log.warn(msg)
               throw ContainerException(msg, it)
            }
         )
      }
   }

   private fun internalExecute(
      command: List<String>,
      workingDir: String? = null,
      useTty: Boolean = false,
      input: InputStream? = null,
      output: OutputStream = OutputStream.nullOutputStream(),
      waitTimeValue: Long? = null,
      waitTimeUnit: TimeUnit? = null,
   ): Pair<Int?, String?> {
      container.requireOneOfStates(Container.State.RUNNING)

      log.trace("Executing command: ${command.joinToString(SPACE)}")

      val latch = CountDownLatch(1)

      val callback = object : ResultCallback<Frame> {

         override fun close() {
            log.trace("Exec command callback: close")
         }

         override fun onStart(closeable: Closeable?) {
            log.trace("Exec command callback: onStart")
         }

         override fun onNext(frame: Frame?) {
            frame?.payload?.let {
               log.trace("Exec command callback: onNext (with frame): ${it.decodeToString()}")
               output.write(it)
            }
         }

         override fun onError(error: Throwable?) {
            log.error("Exec command callback: onError: ${error?.message}", error)
            latch.countDown()
         }

         override fun onComplete() {
            log.trace("Exec command callback: onComplete")
            latch.countDown()
         }
      }

      val execCreateCmdResponse = dockerClient.execCreateCmd(containerId.get())
         .withAttachStdout(true)
         .withAttachStderr(false)
         .withCmd(*command.toTypedArray())
         .withTty(useTty)
         .apply {
            workingDir?.let { withWorkingDir(workingDir) }
            input?.let { withAttachStdin(true) }
         }
         .exec()

      val execId = execCreateCmdResponse.id

      dockerClient.execStartCmd(execId)
         .withTty(useTty)
         .apply { input?.let { withStdIn(input) } }
         .exec(callback)

      waitTimeValue?.let {
         waitTimeUnit?.let { TimeUnit.MILLISECONDS }?.let { it1 -> latch.await(waitTimeValue, it1) }
      } ?: run {
         latch.await() // Wait indefinitely
      }

      val execInspectCmdResponse = dockerClient
         .inspectExecCmd(execId)
         .exec()
      val exitCode = execInspectCmdResponse.exitCodeLong?.toInt()

      return Pair(exitCode, null)
   }

   private fun getDisplayName(): String = "${container.getName()} (${containerId.get()})"

}
