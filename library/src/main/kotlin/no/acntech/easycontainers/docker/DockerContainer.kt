package no.acntech.easycontainers.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.*
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.model.*
import com.github.dockerjava.api.model.Volume
import no.acntech.easycontainers.*
import no.acntech.easycontainers.docker.DockerConstants.DEFAULT_BRIDGE_NETWORK
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.model.Container
import no.acntech.easycontainers.util.io.FileUtils
import no.acntech.easycontainers.util.io.FileUtils.tarFile
import no.acntech.easycontainers.util.platform.PlatformUtils.convertToDockerPath
import no.acntech.easycontainers.util.text.EMPTY_STRING
import no.acntech.easycontainers.util.text.SPACE
import no.acntech.easycontainers.util.time.DurationFormatter
import java.io.*
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal class DockerContainer(
   containerBuilder: ContainerBuilder,
   private val dockerClient: DockerClient = DockerClientFactory.createDefaultClient(),
) : AbstractContainer(containerBuilder) {

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
      private var SCHEDULER: ScheduledExecutorService = Executors.newScheduledThreadPool(
         1,
         Thread.ofVirtual().factory());

      private val EXECUTOR_SERVICE = Executors.newVirtualThreadPerTaskExecutor()
   }

   private val containerId: AtomicReference<String> = AtomicReference()

   private var exitCode: Int? = null

   private var ipAddress: InetAddress? = null

   private var host: Host? = null

   private var networkName: NetworkName? = null

   private var startedAt: Instant? = null

   private var finishedAt: Instant? = null



   override fun run() {
      changeState(Container.State.STARTED)
      pullImage()
      startContainer()
      EXECUTOR_SERVICE.submit(LogWatcher())
      extractStartTime()
   }

   override fun waitForCompletion(timeoutValue: Long, timeoutUnit: TimeUnit): Int {
      log.info("Waiting for container to complete: '${getName()}' ($containerId)")
      val callback = dockerClient.waitContainerCmd(containerId.get()).exec(WaitContainerResultCallback())
      val statusCode = callback.awaitStatusCode(timeoutValue, timeoutUnit)
      exitCode = statusCode
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

   override fun getType(): ContainerType {
      return builder.containerType
   }

   override fun getExecutionMode(): ExecutionMode {
      return builder.executionMode
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

   override fun getExitCode(): Int? {
      if (exitCode == null && containerId.get() != null) {
         val containerInfo = dockerClient.inspectContainerCmd(containerId.get()).exec()
         exitCode = containerInfo.state.exitCodeLong?.toInt()
      }
      return exitCode
   }

   override fun execute(
      executable: Executable,
      args: Args?,
      workingDir: UnixDir?,
      input: InputStream?,
      waitTimeValue: Long?,
      waitTimeUnit: TimeUnit?,
   ): Triple<Int, String, String> {
      return internalExecute(
         listOf(executable.value) + (args?.toStringList() ?: emptyList()),
         workingDir?.value,
         input,
         waitTimeValue,
         waitTimeUnit,
      )
   }

   override fun putFile(localPath: Path, remoteDir: UnixDir, remoteFilename: String?) {
      requireState(Container.State.RUNNING)

      require(localPath.exists()) { "Local file '$localPath' does not exist" }
      require(localPath.isRegularFile()) { "Local path '$localPath' is not a file" }

      // Check if remoteDir exists, if not create it
      createContainerDirIfNotExists(remoteDir)

      val tarFile = tarFile(localPath, remoteFilename ?: localPath.fileName.toString())
      val inputStream = FileInputStream(tarFile)
      try {
         dockerClient.copyArchiveToContainerCmd(containerId.get())
            .withRemotePath(remoteDir.value)
            .withTarInputStream(inputStream)
            .exec()

         log.info("File '${localPath.fileName}' uploaded to container '${getName()}' ('$containerId') " +
            "in directory '${remoteDir.value}'")

      } catch (e: Exception) {
         handleDockerError(e)
      } finally {
         inputStream.close()
         tarFile.delete()
      }
   }

   override fun getFile(remoteDir: UnixDir, remoteFilename: String, localPath: Path?): Path {
      val tempTarFile = Files.createTempFile("temp", ".tar").toFile()

      val remoteFilePath = "$remoteDir/$remoteFilename"

      try {
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
      } catch(e: Exception) {
         handleDockerError(e)
         // Not reached
         return Paths.get(EMPTY_STRING)
      } finally {
         tempTarFile.delete()
      }
   }

   override fun putDirectory(localPath: Path, remoteDir: UnixDir) {
      requireState(Container.State.RUNNING)
      require(localPath.exists() && localPath.isDirectory()) { "Local directory '$localPath' does not exist" }

      // Check if remoteDir exists, if not create it
      createContainerDirIfNotExists(remoteDir)

      val tarFile = FileUtils.tarDir(localPath)

      val inputStream = FileInputStream(tarFile)
      try {
         dockerClient.copyArchiveToContainerCmd(containerId.get())
            .withRemotePath(remoteDir.value)
            .withTarInputStream(inputStream)
            .exec()

         log.info("Directory'$localPath' uploaded to container '${getName()}' ('$containerId') " +
            "in directory '${remoteDir.value}'")

      } catch (e: Exception) {
         handleDockerError(e)
      } finally {
         inputStream.close()
         tarFile.delete()
      }
   }

   override fun getDirectory(remoteDir: UnixDir, localPath: Path) {
      val tarBall = Files.createTempFile("docker-download-tarball", ".tar").toFile()

      try {
         FileOutputStream(tarBall).use { outputStream ->
            dockerClient.copyArchiveFromContainerCmd(containerId.get(), remoteDir.value).exec().use { inputStream ->
               inputStream.copyTo(outputStream)
            }
         }

         if (Files.isDirectory(localPath)) {
            FileUtils.untarDir(tarBall, localPath)
         }
      } catch (ex: Exception) {
         handleDockerError(ex)
      } finally {
         if (!tarBall.delete()) {
            log.warn("Failed to delete temporary tarball: ${tarBall.absolutePath}")
         }
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
         handleDockerError(e)
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

         changeState(Container.State.RUNNING).also {
            log.info("Container '${getName()}' ('$containerId') is RUNNING with IP-address '$ipAddress' and hostname '$host'")
         }

      } catch (e: Exception) {
         handleDockerError(e)
      }
   }

   private fun prepareHostConfig(): HostConfig {
      return HostConfig.newHostConfig().apply {
         if (builder.isEphemeral) {
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
         configurePortBindings(hostConfig)
         configureVolumes(this, hostConfig)
         configureCommandAndArgs(this)

         // We want to mimic the behaviour of k8s here, so we override the entrypoint if the command or args are set
         if (builder.command != null || (builder.args != null && builder.args!!.args.isNotEmpty())) {
            withEntrypoint("/bin/sh", "-c")
         }

         withHostConfig(hostConfig)
      }
   }

   private fun configurePortBindings(hostConfig: HostConfig) {
      if (builder.portMappings.isNotEmpty()) {
         val portBindings = getPortBindings()
         hostConfig.withPortBindings(portBindings)
      } else {
         log.debug("No ports to bind")
      }
   }

   private fun configureVolumes(cmd: CreateContainerCmd, hostConfig: HostConfig) {
      if (builder.volumes.isNotEmpty()) {
         val volumes = createDockerVolumes(hostConfig)
         cmd.withVolumes(*volumes.toTypedArray())
      } else {
         log.debug("No volumes to bind")
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

   private fun configureCommandAndArgs(cmd: CreateContainerCmd) {
      val commandParts = mutableListOf<String>().apply {
         builder.command?.let { addAll(it.value.split("\\s".toRegex())) }
         builder.args?.let { addAll(it.toStringList()) }
      }
      cmd.withCmd(commandParts)
      log.info("Using container command: $commandParts")
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

   private fun internalExecute(
      command: List<String>,
      workingDir: String? = null,
      input: InputStream? = null,
      waitTimeValue: Long? = null,
      waitTimeUnit: TimeUnit? = null,
   ): Triple<Int, String, String> {
      requireState(Container.State.RUNNING)

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
         .apply {
            workingDir?.let { withWorkingDir(workingDir) }
            input?.let { withAttachStdin(true) }
         }
         .exec()

      val execId = execCreateCmdResponse.id

      dockerClient.execStartCmd(execId)
         .apply {
            input?.let { withStdIn(input) }
         }
         .exec(callback)

      waitTimeValue?.let {
         waitTimeUnit?.let { TimeUnit.MILLISECONDS }?.let { it1 -> latch.await(waitTimeValue, it1) }
      } ?: run {
         latch.await() // Wait indefinitely
      }

      val execInspectCmdResponse = dockerClient.inspectExecCmd(execId).exec()
      val exitCode = execInspectCmdResponse.exitCodeLong.toInt()

      return Triple(exitCode, outputBuilder.toString(), EMPTY_STRING)
   }

   private fun createContainerDirIfNotExists(remoteDir: UnixDir) {
      val (exitValue, stdOut, stdErr) = internalExecute(listOf("mkdir", "-p", remoteDir.value))
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

   private fun handleDockerError(e: Exception, swallow: Boolean = false) {
      when (e) {
         is DockerException, is DockerClientException -> {
            log.error("A Docker error '{}' occurred in container '${builder.name}', raising ContainerException", e.message, e)
            changeState(Container.State.FAILED)
            if (!swallow) {
               throw ContainerException("Error '${e.message} in container ${builder.name}", e)
            }
         }

         else -> {
            log.error("An error '{}' occurred in container '${builder.name}', re-throwing", e.message, e)
            changeState(Container.State.FAILED)
            if (!swallow) {
               throw e;
            }
         }
      }
   }


}