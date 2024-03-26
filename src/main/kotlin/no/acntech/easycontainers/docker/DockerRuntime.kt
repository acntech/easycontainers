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
import no.acntech.easycontainers.AbstractContainerRuntime
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.io.FileUtils
import no.acntech.easycontainers.util.lang.guardedExecution
import no.acntech.easycontainers.util.platform.PlatformUtils
import no.acntech.easycontainers.util.text.EMPTY_STRING
import no.acntech.easycontainers.util.text.SPACE
import no.acntech.easycontainers.util.text.splitOnWhites
import org.awaitility.Awaitility.await
import org.awaitility.core.ConditionTimeoutException
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
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

   private inner class LogWatcher : Runnable {
      override fun run() {
         dockerClient.logContainerCmd(containerId.get())
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .withTailAll()
            .exec(object : ResultCallback.Adapter<Frame>() {

               override fun onNext(item: Frame) {
                  val line = item.payload.decodeToString()
                  log.trace("Container '${getDisplayName()}' output: $line")
                  container.getOutputLineCallback().onLine(line)
               }

               override fun onError(throwable: Throwable) {
                  log.warn("Container '${getDisplayName()}' output error", throwable)
               }

               override fun onComplete() {
                  log.info("Container '${getDisplayName()}' output complete")
               }

            }).awaitCompletion()
      }
   }

   private val containerId: AtomicReference<String> = AtomicReference()

   private var exitCode: Int? = null

   private var ipAddress: InetAddress? = null

   private var host: Host? = null

   private var networkName: NetworkName? = null

   private var startedAt: Instant? = null

   private var finishedAt: Instant? = null

   override fun getType(): ContainerPlatformType {
      return ContainerPlatformType.DOCKER
   }

   override fun start() {
      container.changeState(ContainerState.INITIALIZING, ContainerState.UNINITIATED)
      pullImage()
      createAndStartContainer()
      GENERAL_EXECUTOR_SERVICE.submit(LogWatcher())
      super.start()
      container.changeState(ContainerState.RUNNING, ContainerState.INITIALIZING)
   }

   override fun stop() {
      container.changeState(ContainerState.TERMINATING, ContainerState.RUNNING)

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
            val info = inspectContainer()
            setFinishedTime(info)
            setExitCode(info)
         },
         listOf(DockerException::class, DockerClientException::class),
         {
            val msg = "Error '${it.message} stopping (Docker) container: ${getDisplayName()}"
            log.warn(msg)
            throw ContainerException(msg, it)
         },
         finallyBlock = {
            super.stop()
            container.changeState(ContainerState.STOPPED)
         }
      )
   }

   override fun kill() {
      container.changeState(ContainerState.TERMINATING, ContainerState.RUNNING)

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
            val info = inspectContainer()
            setFinishedTime(info)
            setExitCode(info)
         },
         listOf(DockerException::class, DockerClientException::class),
         {
            val msg = "Error '${it.message} killing (Docker) container: ${getDisplayName()}"
            log.warn(msg)
            throw ContainerException(msg, it)
         }
      )

      container.changeState(ContainerState.STOPPED)
   }

   override fun delete(force: Boolean) {
      if (container.getState() == ContainerState.RUNNING) {
         kill()
      }

      if (!force) {
         container.requireOneOfStates(ContainerState.STOPPED, ContainerState.FAILED)
      }

      if (container.isEphemeral()) {
         log.debug("Container is ephemeral and thus already removed: ${getDisplayName()}")
         cleanUpResources()
         container.changeState(ContainerState.DELETED)

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

         container.changeState(ContainerState.DELETED)
      }
   }

   override fun execute(
      executable: Executable,
      args: Args?,
      useTty: Boolean,
      workingDir: UnixDir?,
      input: InputStream?,
      waitTimeValue: Long?,
      waitTimeUnit: TimeUnit?,
   ): Triple<Int?, String, String> {
      return internalExecute(
         listOf(executable.value) + (args?.toStringList() ?: emptyList()),
         workingDir?.value,
         useTty,
         input,
         waitTimeValue,
         waitTimeUnit,
      )
   }

   override fun putFile(localPath: Path, remoteDir: UnixDir, remoteFilename: String?) {
      require(localPath.exists()) { "Local file '$localPath' does not exist" }
      require(localPath.isRegularFile()) { "Local path '$localPath' is not a file" }
      container.requireOneOfStates(ContainerState.RUNNING)

      // Check if remoteDir exists, if not create it
      createContainerDirIfNotExists(remoteDir)

      val tarFile = FileUtils.tarFile(localPath, remoteFilename ?: localPath.fileName.toString())
      val inputStream = FileInputStream(tarFile)

      guardedExecution(
         {
            dockerClient.copyArchiveToContainerCmd(containerId.get())
               .withRemotePath(remoteDir.value)
               .withTarInputStream(inputStream)
               .exec()

            log.info("File '${localPath.fileName}' uploaded to container '${getDisplayName()}' in directory '${remoteDir.value}'")
         },
         listOf(DockerException::class, DockerClientException::class),
         {
            val msg = "Error '${it.message}' putting file: ${localPath.fileName} to container: ${getDisplayName()}"
            log.warn(msg)
            throw ContainerException(msg, it)
         },
         finallyBlock = {
            guardedExecution({ inputStream.close() })
            guardedExecution({ tarFile.delete() })
         }
      )
   }

   override fun getFile(remoteDir: UnixDir, remoteFilename: String, localPath: Path?): Path {
      val tempTarFile = Files.createTempFile("temp", ".tar").toFile()

      val remoteFilePath = "$remoteDir/$remoteFilename"

      guardedExecution(
         {
            dockerClient
               .copyArchiveFromContainerCmd(containerId.get(), remoteFilePath)
               .exec()
               .use { responseStream ->
                  FileOutputStream(tempTarFile).use { outputStream ->
                     responseStream.copyTo(outputStream)
                  }
               }

            val targetPath = determineLocalPath(localPath, remoteFilename)

            // untar the tempTarFile to the targetPath
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

   override fun putDirectory(localPath: Path, remoteDir: UnixDir) {
      require(localPath.exists() && localPath.isDirectory()) { "Local directory '$localPath' does not exist" }
      container.requireOneOfStates(ContainerState.RUNNING)

      // Check if remoteDir exists, if not create it
      createContainerDirIfNotExists(remoteDir)

      val tarFile = FileUtils.tarDir(localPath)
      val inputStream = FileInputStream(tarFile)


      guardedExecution(
         {
            dockerClient.copyArchiveToContainerCmd(containerId.get())
               .withRemotePath(remoteDir.value)
               .withTarInputStream(inputStream)
               .exec()
            log.info("Directory'$localPath' uploaded to container '${getDisplayName()} in directory '${remoteDir.value}'")
         },
         listOf(DockerException::class, DockerClientException::class),
         {
            val msg = "Error '${it.message}' putting directory: $localPath to container: ${getDisplayName()}"
            log.warn(msg)
            throw ContainerException(msg, it)
         },
         finallyBlock = {
            guardedExecution({ inputStream.close() })
            guardedExecution({ tarFile.delete() })
         })
   }

   override fun getDirectory(remoteDir: UnixDir, localPath: Path) {
      val tarBall = Files.createTempFile("docker-download-tarball", ".tar").toFile()

      guardedExecution(
         {
            FileOutputStream(tarBall).use { outputStream ->
               dockerClient.copyArchiveFromContainerCmd(containerId.get(), remoteDir.value).exec().use { inputStream ->
                  inputStream.copyTo(outputStream)
               }
            }

            if (Files.isDirectory(localPath)) {
               FileUtils.untarDir(tarBall, localPath)
            }
         },
         listOf(DockerException::class, DockerClientException::class),
         {
            val msg = "Error '${it.message}' getting directory '$remoteDir' from container' ${getDisplayName()}'"
            log.warn(msg)
            throw ContainerException(msg, it)
         },
         finallyBlock = {
            if (!tarBall.delete()) {
               log.warn("Failed to delete temporary tarball: ${tarBall.absolutePath}")
            }
         }
      )
   }

   override fun getDuration(): Duration? {
      return startedAt?.let { start ->
         if (finishedAt == null) {
            setFinishedTime(inspectContainer())
         }
         val end = finishedAt ?: Instant.now()
         Duration.between(start, end)
      }
   }

   override fun getExitCode(): Int? {
      if (exitCode == null && containerId.get() != null) {
         val containerInfo = dockerClient.inspectContainerCmd(containerId.get()).exec()
         exitCode = containerInfo.state.exitCodeLong?.toInt()
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

            val containerCmd = createContainerCommand(image, hostConfig)
            containerCmd.exec().id.also {
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
            container.changeState(ContainerState.FAILED)
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

   private fun determineLocalPath(localPath: Path?, remoteFilename: String): Path {
      if (localPath == null) {
         return Paths.get(System.getProperty("user.dir"), remoteFilename)
      }

      return if (Files.isDirectory(localPath)) {
         localPath.resolve(remoteFilename)
      } else {
         localPath
      }
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
      containerInfo.state.finishedAt?.let {
         finishedAt = Instant.parse(it)
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

         // We want to mimic the behaviour of k8s here, so we override the entrypoint if the command or args are set
         if (container.getCommand() != null || (container.getArgs() != null && container.getArgs()!!.args.isNotEmpty())) {
            withEntrypoint("/bin/sh", "-c")
         }

         withHostConfig(hostConfig)
      }
   }

   private fun createDockerVolumes(hostConfig: HostConfig): List<Volume> {
      val volumeMap = container.getVolumes().associateWith { Volume(it.mountPath.value) }
      val dockerVolumeNames = getExistingVolumeNames()

      val binds = container.getVolumes().filter { it.memoryBacked }
         .map { volume -> createBind(volume, volumeMap[volume], dockerVolumeNames) }

      val tmpfsMounts = container.getVolumes().filter { it.memoryBacked }
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

   private fun createContainerDirIfNotExists(remoteDir: UnixDir) = internalExecute(listOf("mkdir", "-p", remoteDir.value))

   private fun configureNetwork(hostConfig: HostConfig) {
      container.getNetworkName()?.let { networkName ->
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

   private fun configurePortBindings(hostConfig: HostConfig) {
      if (container.getPortMappings().isNotEmpty()) {
         val portBindings = getDockerPortBindings()
         hostConfig.withPortBindings(portBindings)
      } else {
         log.debug("No ports to bind for container: ${getDisplayName()}")
      }
   }

   private fun configureVolumes(cmd: CreateContainerCmd, hostConfig: HostConfig) {
      if (container.getVolumes().isNotEmpty()) {
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
      waitTimeValue: Long? = null,
      waitTimeUnit: TimeUnit? = null,
   ): Triple<Int?, String, String> {
      container.requireOneOfStates(ContainerState.RUNNING)

      log.trace("Executing command: ${command.joinToString(SPACE)}")

      val latch = CountDownLatch(1)

      val outputBuilder: StringBuilder = StringBuilder()

      val callback = object : ResultCallback<Frame> {

         override fun close() {
            log.trace("Exec command callback: Closed")
         }

         override fun onStart(closeable: Closeable?) {
            log.trace("Exec command callback: Started")
         }

         override fun onNext(frame: Frame?) {
            val line = frame?.payload?.decodeToString()
            log.trace("Exec command callback: Output: $line")
            outputBuilder.append(line)
         }

         override fun onError(error: Throwable?) {
            log.error("Exec command callback: Error (${error?.message}", error)
            latch.countDown()
         }

         override fun onComplete() {
            log.trace("Exec command callback: Completed")
            latch.countDown()
         }
      }

      val execCreateCmdResponse = dockerClient.execCreateCmd(containerId.get())
         .withAttachStdout(true)
         .withAttachStderr(true)
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

      return Triple(exitCode, outputBuilder.toString(), EMPTY_STRING)
   }

   private fun getDisplayName(): String = "${container.getName()} (${containerId.get()})"

}
