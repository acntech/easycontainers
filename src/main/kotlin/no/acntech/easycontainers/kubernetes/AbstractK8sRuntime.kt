package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.client.*
import io.fabric8.kubernetes.client.dsl.ExecListener
import io.fabric8.kubernetes.client.dsl.ExecWatch
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.utils.Serialization
import no.acntech.easycontainers.AbstractContainerRuntime
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.kubernetes.K8sConstants.MEDIUM_MEMORY_BACKED
import no.acntech.easycontainers.kubernetes.K8sUtils.normalizeLabelValue
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.model.ContainerState
import no.acntech.easycontainers.util.collections.prettyPrint
import no.acntech.easycontainers.util.io.closeQuietly

import no.acntech.easycontainers.util.text.NEW_LINE
import no.acntech.easycontainers.util.text.SPACE
import org.awaitility.Awaitility
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference


/**
 * Represents an abstract Kubernetes runtime for a container, capturing common functionality and properties for both
 * Kubernetes Jobs and Deployments.
 *
 * @property client The Kubernetes client used to interact with the Kubernetes cluster.
 * @property accessChecker The access checker used to check access permissions.
 * @property pods The list of pods associated with the container.
 * @property configMaps The list of config maps associated with the container.
 * @property selectorLabels The selector labels used to filter pods.
 * @property ourDeploymentName The name of our deployment.
 * @property containerLogStreamer The container log streamer used to stream container logs.
 * @property host The host associated with the container.
 * @property ipAddress The IP address of the container.
 * @property startedAt The time when the container started.
 * @property finishedAt The time when the container finished.
 * @property exitCode The exit code of the container.
 * @property podPhaseLatches The latches used to wait for pod phases.
 */
abstract class AbstractK8sRuntime(
   container: GenericContainer,
   protected val client: KubernetesClient = K8sClientFactory.createDefaultClient(),
) : AbstractContainerRuntime(container) {

   private inner class PodWatcher : Watcher<Pod> {

      override fun eventReceived(action: Watcher.Action?, pod: Pod?) {
         val podInfo = mutableMapOf<String, Any?>()

         podInfo["PodWatcher eventReceived"] = action
         podInfo["Pod phase"] = pod?.status?.phase
         podInfo["Pod status"] = pod?.status?.containerStatuses?.joinToString { it.state.toString() }

         pod?.status?.conditions?.forEach { condition ->
            podInfo["${condition.type} Condition Type"] = condition.type
            podInfo["${condition.type} Condition Status"] = condition.status
            podInfo["${condition.type} Condition Last Probe Time"] = condition.lastProbeTime
            podInfo["${condition.type} Condition Last Transition Time"] = condition.lastTransitionTime
         }

         log.debug("Pod Information:$NEW_LINE${podInfo.prettyPrint()}")

         val podPhase = pod?.status?.phase?.uppercase()?.let {
            PodPhase.valueOf(it)
         }

         // Notify the latch associated with the pod phase
         podPhase?.let {
            podPhaseLatches[it]?.countDown()
         }

         refreshPodsAndCheckStatus()
      }

      override fun onClose(cause: WatcherException?) {
         refreshPodsAndCheckStatus()
      }

   }

   companion object {
      const val CONTAINER_NAME_SUFFIX = "-container"
      const val POD_NAME_SUFFIX = "-pod"
      const val DEPLOYMENT_NAME_SUFFIX = "-deployment"
      const val SERVICE_NAME_SUFFIX = "-service"
      const val PV_NAME_SUFFIX = "-pv"
      const val PVC_NAME_SUFFIX = "-pvc"

      private const val FILE_TRANSFER_NOT_SUPPORTED_MSG = "Direct file transfer not supported for Kubernetes containers"
   }

   protected val accessChecker = AccessChecker(client)

   protected val pods: MutableList<Pair<Pod, List<Container>>> = CopyOnWriteArrayList()

   protected val configMaps: MutableList<ConfigMap> = mutableListOf()

   protected val selectorLabels: Map<String, String> = mapOf(K8sConstants.APP_LABEL to container.getName().value)

   protected val ourDeploymentName: String? = extractOurDeploymentName()

   protected var containerLogStreamer: ContainerLogStreamer? = null

   protected var host: Host? = null

   protected val ipAddress: AtomicReference<InetAddress> = AtomicReference()

   protected val startedAt: AtomicReference<Instant> = AtomicReference()

   protected val finishedAt: AtomicReference<Instant> = AtomicReference()

   protected val exitCode: AtomicReference<Int> = AtomicReference()

   protected val podPhaseLatches: Map<PodPhase, CountDownLatch> = mapOf(
      PodPhase.PENDING to CountDownLatch(1),
      PodPhase.RUNNING to CountDownLatch(1),
      PodPhase.FAILED to CountDownLatch(1),
      PodPhase.SUCCEEDED to CountDownLatch(1),
   )

   override fun delete(force: Boolean) {
      val configMapsExists = client.configMaps()
         .inNamespace(getNamespace())
         .list()
         .items
         .any { item -> configMaps.any { backItem -> backItem.metadata.name == item.metadata.name } }

      if (configMapsExists) {
         configMaps.forEach { configMap ->
            try {
               client.configMaps()
                  .inNamespace(getNamespace())
                  .withName(configMap.metadata.name)
                  .delete()
            } catch (e: Exception) {
               log.error("Error deleting config map '${configMap.metadata.name}': ${e.message}", e)
            }
         }
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
      val (pod, container) = prepareCommandExecution()
      val command = listOf(executable.unwrap()) + args?.toStringList().orEmpty()

      log.debug("Executing command: ${command.joinToString(" ")} in container: ${container.name} in pod: ${pod.metadata.name}")

      val stdOut = ByteArrayOutputStream()
      val stdErr = ByteArrayOutputStream()
      val exitCode = AtomicReference<Int>()
      val error = AtomicReference<Throwable>()

      var execWatch: ExecWatch? = null

      val started = CountDownLatch(1)
      try {
         execWatch = executeCommand(pod, container, command, useTty, stdOut, stdErr, input, started, error)

         // Wait for the execWatch to open before processing input
         waitForLatch(started, waitTimeValue, waitTimeUnit)

//         processPodExecInput(input, execWatch)

         waitForExecutionToFinish(execWatch, waitTimeValue, waitTimeUnit, exitCode, error)
      } catch (e: Exception) {
         handleK8sException(e)
      } finally {
         execWatch?.closeQuietly()
      }

      error.get()?.let {
         throw ContainerException("Error executing command: ${it.message}", it)
      }

//      assert(exitCode.get() != null) { "Exit code should not be null" }

      return Triple(exitCode.get(), stdOut.toString(), stdErr.toString())
   }

   /**
    * This method is not supported for Kubernetes containers.
    * TODO: Might be implemented using a common file storage.
    */
   override fun putFile(localPath: Path, remoteDir: UnixDir, remoteFilename: String?) {
      throw UnsupportedOperationException(FILE_TRANSFER_NOT_SUPPORTED_MSG)
   }

   /**
    * This method is not supported for Kubernetes containers.
    * TODO: Might be implemented using a common file storage.
    */
   override fun putDirectory(localPath: Path, remoteDir: UnixDir) {
      throw UnsupportedOperationException(FILE_TRANSFER_NOT_SUPPORTED_MSG)
   }

   /**
    * This method is not supported for Kubernetes containers.
    * TODO: Might be implemented using a common file storage.
    */
   override fun getFile(remoteDir: UnixDir, remoteFilename: String, localPath: Path?): Path {
      throw UnsupportedOperationException(FILE_TRANSFER_NOT_SUPPORTED_MSG)
   }

   /**
    * This method is not supported for Kubernetes containers.
    * TODO: Might be implemented using a common file storage.
    */
   override fun getDirectory(remoteDir: UnixDir, localPath: Path) {
      throw UnsupportedOperationException(FILE_TRANSFER_NOT_SUPPORTED_MSG)
   }

   override fun getDuration(): Duration? {
      return startedAt.get()?.let { start ->
         val end = finishedAt.get() ?: Instant.now()
         Duration.between(start, end)
      }
   }

   override fun getExitCode(): Int? {
      return exitCode.get()
   }

   override fun getHost(): Host? {
      return host
   }

   override fun getIpAddress(): InetAddress? {
      return ipAddress.get()
   }

   override fun getType(): ContainerPlatformType {
      return ContainerPlatformType.KUBERNETES
   }

   protected fun watchPods() {
      client.pods()
         .inNamespace(container.getNamespace().value)
         .withLabels(selectorLabels)
         .watch(PodWatcher())
   }

   protected fun createNamespaceIfAllowedAndNotExists() {
      if (!(accessChecker.canListNamespaces() and accessChecker.canCreateNamespaces())) {
         log.warn("Not allowed to list or create namespaces")
         return
      }

      val namespaceExists = client.namespaces().list().items.any { it.metadata.name == container.getNamespace().value }
      if (!namespaceExists) {
         val namespaceResource =
            NamespaceBuilder().withNewMetadata().withName(container.getNamespace().value).endMetadata().build()
         client.namespaces().resource(namespaceResource).create().also {
            log.info("Created k8s namespace: $it")
         }
      }
   }

   protected fun createContainer(): io.fabric8.kubernetes.api.model.Container {
      val requests = mutableMapOf<String, Quantity>()
      val limits = mutableMapOf<String, Quantity>()

      container.builder.cpuRequest?.let {
         requests["cpu"] = Quantity(it.toString())
      }

      container.builder.memoryRequest?.let {
         requests["memory"] = Quantity(it.toString())
      }

      container.builder.cpuLimit?.let {
         limits["cpu"] = Quantity(it.toString())
      }

      container.builder.memoryLimit?.let {
         limits["memory"] = Quantity(it.toString())
      }

      val resourceRequirements = ResourceRequirements()
      resourceRequirements.requests = requests
      resourceRequirements.limits = limits

      return Container().apply {
         name = "${container.getName().value}$CONTAINER_NAME_SUFFIX"
         image = container.getImage().toFQDN()
         env = container.getEnv().map { EnvVar(it.key.value, it.value.value, null) }
         ports = container.getExposedPorts().map {
            ContainerPort(it.value, null, null, null, "TCP")
         }
         container.getCommand()?.let {
            command = it.value.split(SPACE)
         }
         container.getArgs()?.let {
            args = it.toStringList()
         }
         resources = resourceRequirements
      }
   }

   protected fun createVolumes(): Pair<List<Volume>, List<VolumeMount>> {
      val volumes = mutableListOf<Volume>()
      val volumeMounts = mutableListOf<VolumeMount>()

      // Handle container files
      handleContainerFiles(volumes, volumeMounts)

      // Handle existing persistent volumes and claims
      handlePersistentVolumes(volumes, volumeMounts)

      // Handle memory-backed volumes
      handleMemoryBackedVolumes(volumes, volumeMounts)

      return Pair(volumes, volumeMounts)
   }

   protected fun createDefaultLabels(): Map<String, String> {
      val defaultLabels: MutableMap<String, String> = mutableMapOf()

      val parentAppName = normalizeLabelValue(
         System.getProperty("spring.application.name")
            ?: "${System.getProperty("java.vm.name")}-${ProcessHandle.current().pid()}"
      )

      defaultLabels["acntech.no/created-by"] = "Easycontainers"
      defaultLabels["easycontainers.acntech.no/created-at"] = K8sUtils.instantToLabelValue(Instant.now())
      defaultLabels["easycontainers.acntech.no/parent-application"] = parentAppName
      defaultLabels["easycontainers.acntech.no/is-ephemeral"] = container.isEphemeral().toString()

      container.getMaxLifeTime()?.let {
         defaultLabels["easycontainers.acntech.no/max-life-time"] = it.toString()
      }

      if (K8sUtils.isRunningInsideCluster()) {
         defaultLabels["easycontainers.acntech.no/parent-running-inside-cluster"] = "true"
         defaultLabels["easycontainers.acntech.no/parent-deployment"] = ourDeploymentName!!
      } else {
         defaultLabels["easycontainers.acntech.no/parent-running-inside-cluster"] = "false"
      }

      return defaultLabels.toMap()
   }


   protected fun createPodSpec(container: Container, volumes: List<Volume>): PodSpec {
      return PodSpec().apply {
         containers = listOf(container)
         this.volumes = volumes
      }
   }

   protected fun extractPodsAndContainers(maxWaitTimeSeconds: Long = 30L) {
      var extractedPods: List<Pod> = emptyList()

      Awaitility.await()
         .atMost(maxWaitTimeSeconds, TimeUnit.SECONDS)
         .pollInterval(500, TimeUnit.MILLISECONDS)
         .until {
            extractedPods = client.pods().inNamespace(container.getNamespace().value).withLabels(selectorLabels).list().items
            extractedPods.isNotEmpty()
         }

      if (extractedPods.isEmpty()) {
         throw ContainerException(
            "Timer expired ($maxWaitTimeSeconds seconds) waiting for pods"
         )
      }

      // INVARIANT: pods is not empty
      if (extractedPods.size > 1) {
         log.warn("Multiple pods found: ${extractedPods.joinToString { it.metadata.name }}")
      }

      // Get the IP address of the first pod in the list (most likely the only one)
      ipAddress.set(InetAddress.getByName(extractedPods.first().status.podIP))

      // Add a debug/logging watcher for the pods
      client.pods()
         .inNamespace(getNamespace())
         .withLabels(selectorLabels)
         .watch(LoggingWatcher())

      // Pair each pod with its list of containers
      extractedPods.forEach { pod ->
         pods.add(Pair(pod, pod.spec.containers))
      }

      pods.forEach { (pod, containers) ->
         log.info("Pod: ${pod.metadata.name}")
         containers.forEach { container ->
            log.info("Container: ${container.name}")
         }
      }
   }

   @Throws(ContainerException::class)
   protected fun handleK8sException(e: Exception) {
      when (e) {
         is KubernetesClientException, is ResourceNotFoundException, is WatcherException -> {
            val message = "Kubernetes error: ${e.message}"
            log.error(message, e)
            throw ContainerException(message, e)
         }

         is ContainerException -> throw e

         else -> {
            log.error("Unexpected exception '${e.javaClass.simpleName}:'${e.message}', re-throwing", e)
            throw e // Rethrow the exception if it's not one of the handled types
         }
      }
   }

   protected fun getNamespace(): String = container.getNamespace().value

   private fun handleContainerFiles(
      volumes: MutableList<Volume>,
      volumeMounts: MutableList<VolumeMount>,
   ) {
      container.builder.containerFiles.forEach { (name, configFile) ->
         log.trace("Creating name -> config map mapping: $name -> $configFile")

         // Extract directory and filename from mountPath, ensuring forward slashes
         val mountPath = File(configFile.mountPath.value).parent?.replace("\\", "/") ?: "/"
         val fileName = File(configFile.mountPath.value).name

         // Create a ConfigMap object with a single entry
         val configMap = ConfigMapBuilder()
            .withNewMetadata()
            .withName(name.value)
            .withNamespace(getNamespace())
            .addToLabels(createDefaultLabels())
            .endMetadata()
            .addToData(fileName, configFile.content) // fileName as the key
            .build()

         val configMapResource: Resource<ConfigMap> =
            client.configMaps()
               .inNamespace(getNamespace())
               .resource(configMap)

         configMapResource.get()?.let {
            configMapResource
               .withTimeout(30, TimeUnit.SECONDS)
               .delete()
               .also {
                  log.info("Deleted existing k8s config map: $it")
               }
         }

         configMapResource.create().also {
            configMaps.add(it)
            log.info("Created a k8s config map: $it")
            log.debug("ConfigMap YAML: ${Serialization.asYaml(configMap)}")
         }

         // Create the corresponding Volume
         val volume = VolumeBuilder()
            .withName(name.value)
            .withNewConfigMap()
            .withName(name.value)
            .endConfigMap()
            .build()
         volumes.add(volume)

         // Create the corresponding VolumeMount
         val volumeMount = VolumeMountBuilder()
            .withName(name.value)
            .withMountPath(mountPath)
            .withSubPath(fileName) // Mount only the specific file within the directory
            .build()
         volumeMounts.add(volumeMount)
      }
   }

   private fun handlePersistentVolumes(
      volumes: MutableList<Volume>,
      volumeMounts: MutableList<VolumeMount>,
   ) {
      container.builder.volumes.filter {
         !it.memoryBacked
      }.forEach { volume ->

         // Derive the PVC name from the volume name
         val pvcName = "${volume.name}$PVC_NAME_SUFFIX"

         // Create the Volume using the existing PVC
         val k8sVolume = VolumeBuilder()
            .withName(volume.name.value)
            .withNewPersistentVolumeClaim()
            .withClaimName(pvcName)
            .endPersistentVolumeClaim()
            .build()
         volumes.add(k8sVolume)

         // Create the VolumeMount
         val volumeMount = VolumeMountBuilder()
            .withName(volume.name.value)
            .withMountPath(volume.mountPath.value)
            .build()
         volumeMounts.add(volumeMount)

         log.info("Created persistent volume: ${volume.name.value}")
      }
   }

   private fun handleMemoryBackedVolumes(volumes: MutableList<Volume>, volumeMounts: MutableList<VolumeMount>) {
      container.builder.volumes.filter { it.memoryBacked }.forEach { volume ->
         val emptyDir = EmptyDirVolumeSource()
         emptyDir.medium = MEDIUM_MEMORY_BACKED
         volume.memory?.let {
            emptyDir.sizeLimit = Quantity(it.toFormattedString())
         }

         val k8sVolume = VolumeBuilder()
            .withName(volume.name.value)
            .withEmptyDir(emptyDir)
            .build()
         volumes.add(k8sVolume)

         val volumeMount = VolumeMountBuilder()
            .withName(volume.name.value)
            .withMountPath(volume.mountPath.value)
            .build()
         volumeMounts.add(volumeMount)

         log.info("Created memory-backed volume: ${volume.name.value}")
      }
   }

   private fun extractOurDeploymentName(): String? {
      if (K8sUtils.isRunningOutsideCluster()) {
         return null
      }

      val podName = System.getenv(K8sConstants.ENV_HOSTNAME)  // Pod name from the HOSTNAME environment variable

      // Get the current pod based on the pod name and namespace
      val pod = client.pods()
         .inNamespace(getNamespace())
         .withName(podName)
         .get()

      // Get labels of the current pod
      val podLabels = pod.metadata.labels

      // Query all deployments in the namespace
      val deployments = client.apps().deployments()
         .inNamespace(getNamespace())
         .list().items

      // Find the deployment whose selector matches the pod's labels
      val deployment = deployments.firstOrNull { deployment ->
         deployment.spec.selector.matchLabels.entries.all {
            podLabels[it.key] == it.value
         }
      }

      return deployment?.metadata?.name ?: "unknown"
   }

   private fun prepareCommandExecution(): Pair<Pod, Container> {
      val (pod, containers) = pods.firstOrNull()
         ?: throw IllegalArgumentException("No available pods to execute command")

      if (containers.size > 1) {
         println("Warning: The selected pod has more than one container. The command will be executed in the first container.")
      }

      val container = containers.first()
      return Pair(pod, container)
   }

   private fun executeCommand(
      pod: Pod,
      container: Container,
      command: List<String>,
      useTty: Boolean,
      stdOut: ByteArrayOutputStream,
      stdErr: ByteArrayOutputStream,
      input: InputStream?,
      started: CountDownLatch,
      error: AtomicReference<Throwable>,
   ): ExecWatch {
      val listener = object : ExecListener {

         override fun onOpen() {
            log.trace("ExecListener: onOpen")
            started.countDown()
         }

         override fun onFailure(throwable: Throwable?, response: ExecListener.Response?) {
            log.error("ExecListener: onFailure: ${throwable?.message}", throwable)
            error.set(throwable)
            started.countDown()
         }

         override fun onClose(code: Int, reason: String?) {
            log.debug("ExecListener: onClose: code=$code, reason=$reason")
            exitCode.set(code)
            started.countDown()
         }
      }

      return client
         .pods()
         .inNamespace(pod.metadata.namespace)
         .withName(pod.metadata.name)
         .inContainer(container.name)
         .apply {
            if (input != null) {
//               redirectingInput()
//               this@AbstractK8sRuntime.log.trace("Redirecting input")
               readingInput(input) // Need to apply this since redirectingInput() DOES NOT WORK!
               this@AbstractK8sRuntime.log.trace("Reading input")
            }
            if (useTty) {
               withTTY()
               this@AbstractK8sRuntime.log.trace("TTY enabled")
            }
            terminateOnError()
         }
         .writingOutput(stdOut)
         .writingError(stdErr)

         .usingListener(listener)
         .exec(*command.toTypedArray())
   }

   private fun processPodExecInput(input: InputStream?, execWatch: ExecWatch) {
      input?.use { clientStdin ->
         if (clientStdin.available() == 0) {
            log.warn("No stdin data available for command execution")
            return
         }

         // Wait for execWatch input to be ready
         Awaitility.await()
            .atMost(10, TimeUnit.SECONDS)
            .alias("Waiting for execWatch input to be ready")
            .until { execWatch.input != null }

         execWatch.input?.let { podStdin ->
            podStdin.use { clientStdin.transferTo(it) }
         } ?: log.warn("No pod stdin stream available for command execution")
      }
   }

   private fun waitForExecutionToFinish(
      execWatch: ExecWatch,
      waitTimeValue: Long?,
      waitTimeUnit: TimeUnit?,
      exitCode: AtomicReference<Int>,
      error: AtomicReference<Throwable>,
   ) {
      if (waitTimeValue == null || waitTimeUnit == null) {
         log.trace("Waiting indefinitely for execWatch to finish")
         val exitCodeValue = execWatch.exitCode().get()
         exitCode.compareAndSet(null, exitCodeValue)
      } else {
         log.trace("Waiting $waitTimeValue $waitTimeUnit for execWatch to finish")
         try {
            val exitCodeValue = execWatch.exitCode().get(waitTimeValue, waitTimeUnit)
            exitCode.compareAndSet(null, exitCodeValue)
         } catch (e: TimeoutException) {
            log.warn("Timeout waiting for execWatch to finish")
            throw ContainerException("Timeout waiting for execWatch to finish", e)
         }
      }

      // Check for any errors that occurred during execution
      error.get()?.let {
         log.error("Error during command execution: ${it.message}", it)
         throw ContainerException("Error during command execution: ${it.message}", it)
      }
   }

   private fun waitForLatch(latch: CountDownLatch, waitTimeValue: Long?, waitTimeUnit: TimeUnit?) {
      if (waitTimeValue == null || waitTimeUnit == null) {
         log.trace("Waiting indefinitely for latch to count down")
         latch.await()
      } else {
         log.trace("Waiting $waitTimeValue $waitTimeUnit for latch to count down")
         val notified = latch.await(waitTimeValue, waitTimeUnit)
         if (!notified) {
            throw ContainerException("Timeout waiting $waitTimeValue $waitTimeUnit for latch to count down")
         } else {
            log.trace("Latch counted down!")
         }
      }
   }

   @Synchronized
   private fun refreshPodsAndCheckStatus() {
      log.trace("Refreshing pods and checking status")
      val refreshedPods = refreshAllPods()

      pods.clear()

      if (refreshedPods.isNotEmpty()) {
         handleRefreshedPods(refreshedPods)
      } else {
         log.warn("No pods found when refreshing container: ${container.getName()}")
      }
   }

   private fun refreshAllPods(): MutableList<Pair<Pod, List<Container>>> {
      val refreshedPods = mutableListOf<Pair<Pod, List<Container>>>()
      pods.forEach { (pod, containers) ->
         val refreshedPod = refreshPod(pod)
         refreshedPod?.let {
            refreshedPods.add(Pair(it, containers))
         }
      }
      return refreshedPods
   }

   private fun refreshPod(pod: Pod): Pod? {
      val refreshedPod = client.pods()
         .inNamespace(getNamespace())
         .withName(pod.metadata.name)
         .get()

      refreshedPod?.let {
         log.debug("Pod refreshed: ${it.metadata.name} - current phase: ${it.status?.phase}")
         updatePodState(it)
      }

      return refreshedPod
   }

   private fun updatePodState(refreshedPod: Pod) {

      // Determine the pod phase and update newState accordingly
      val podPhase = refreshedPod.status?.phase?.uppercase()?.let {
         PodPhase.valueOf(it)
      }

      var newState = when (podPhase) {
         PodPhase.PENDING -> ContainerState.INITIALIZING
         PodPhase.RUNNING -> ContainerState.RUNNING
         PodPhase.FAILED -> ContainerState.FAILED
         PodPhase.SUCCEEDED -> ContainerState.STOPPED
         PodPhase.UNKNOWN -> ContainerState.UNKNOWN
         else -> ContainerState.UNKNOWN // Ensures newState has a default value even if podPhase is null
      }

      // Check each container status within the pod
      refreshedPod.status?.containerStatuses?.forEach { containerStatus ->
         // Update start time if the container is running
         containerStatus.state.running?.startedAt?.let {
            startedAt.set(Instant.parse(it))
         }

         // Handle container termination information
         containerStatus.state.terminated?.let { state ->
            log.info(
               "Container ${containerStatus.name} terminated with signal '${state.signal}', " +
                  "reason: ${state.reason}, message: '${state.message}'"
            )

            // Update finish time and exit code upon termination
            state.finishedAt?.let {
               finishedAt.set(Instant.parse(it))
            }

            state.exitCode?.let {
               exitCode.set(it)
            }

            // When a container is terminated, we consider the pod to be STOPPED
            newState = ContainerState.STOPPED
         }
      }

      // Apply the new state to the container object (assuming 'container' is accessible and represents the current container)
      if (container.isLegalStateChange(newState = newState)) {
         container.changeState(newState)
      } else {
         log.warn("Illegal state change attempt in container '${container.getName()}': ${container.getState()} -> $newState")
      }

      // Log pod refreshment details
      log.debug("Pod '${refreshedPod.metadata.name}' - new state: $newState")
   }

   private fun handleRefreshedPods(refreshedPods: MutableList<Pair<Pod, List<Container>>>) {
      if (refreshedPods.size > 1) {
         log.warn("More than one pod (${refreshedPods.size}) found when refreshing Easycontainer: ${container.getName()}")
      }

      pods.addAll(refreshedPods)
      ipAddress.set(InetAddress.getByName(pods.first().first.status.podIP))

      val stringVal = pods.joinToString(", ") { (pod, containers) ->
         "$pod.metadata.name -> ${containers.joinToString { it.name }}"
      }
      log.trace("Pods and containers refreshed: $stringVal")
   }

}
