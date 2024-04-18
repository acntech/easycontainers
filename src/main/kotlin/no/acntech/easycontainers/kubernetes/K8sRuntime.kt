package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.utils.Serialization
import no.acntech.easycontainers.AbstractContainerRuntime
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.kubernetes.K8sConstants.APP_LABEL
import no.acntech.easycontainers.kubernetes.K8sConstants.MEDIUM_MEMORY_BACKED
import no.acntech.easycontainers.kubernetes.K8sUtils.normalizeConfigMapName
import no.acntech.easycontainers.kubernetes.K8sUtils.normalizeLabelValue
import no.acntech.easycontainers.kubernetes.K8sUtils.normalizeVolumeName
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.model.ContainerState
import no.acntech.easycontainers.util.lang.guardedExecution
import no.acntech.easycontainers.util.lang.prettyPrintMe
import no.acntech.easycontainers.util.text.*
import org.awaitility.Awaitility
import org.awaitility.core.ConditionTimeoutException
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

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
abstract class K8sRuntime(
   container: GenericContainer,
   protected val client: KubernetesClient = K8sClientFactory.createDefaultClient(),
) : AbstractContainerRuntime(container) {

   private inner class PodWatcher : Watcher<Pod> {

      override fun eventReceived(action: Watcher.Action?, pod: Pod?) {
         log.trace("PodWatcher '$this' eventReceived on pod '${pod?.metadata?.name}': $action")

         val podPhase = pod?.status?.phase?.uppercase()?.let {
            PodPhase.valueOf(it)
         }

         // Notify the latch associated with the pod phase
         podPhase?.let {
            podPhaseLatches[it]?.countDown()
         }

         refreshPod()
      }

      override fun onClose(cause: WatcherException?) {
         log.info("PodWatcher closed: ${cause?.message}", cause)
         refreshPod()
      }

   }

   companion object {
      const val CONTAINER_NAME_SUFFIX = "-container"
      const val POD_NAME_SUFFIX = "-pod"
      const val DEPLOYMENT_NAME_SUFFIX = "-deployment"
      const val SERVICE_NAME_SUFFIX = "-service"
      const val PV_NAME_SUFFIX = "-pv"
      const val PVC_NAME_SUFFIX = "-pvc"
      const val CONFIG_MAP_NAME_SUFFIX = "-config-map"
      const val VOLUME_NAME_SUFFIX = "-volume"

      private fun mapPodPhaseToContainerState(podPhase: PodPhase?): ContainerState {
         return when (podPhase) {
            PodPhase.PENDING -> ContainerState.INITIALIZING
            PodPhase.RUNNING -> ContainerState.RUNNING
            PodPhase.FAILED -> ContainerState.FAILED
            PodPhase.SUCCEEDED -> ContainerState.STOPPED
            PodPhase.UNKNOWN -> ContainerState.UNKNOWN
            else -> ContainerState.UNKNOWN // Ensures newState has a default value even if podPhase is null
         }
      }
   }

   protected val accessChecker = AccessChecker(client)

   protected val completionLatch: CountDownLatch = CountDownLatch(1)

   protected val containerName
      get() = k8sContainer.name ?: throw IllegalStateException("No container present")

   protected val configMaps: MutableList<ConfigMap> = mutableListOf()

   protected val selectorLabels: Map<String, String> = mapOf(APP_LABEL to container.getName().value)

   protected val ourDeploymentName: String? = extractOurDeploymentName()

   protected var host: Host? = null

   private val _k8sContainer = AtomicReference<Container>()
   protected var k8sContainer: Container
      get() = _k8sContainer.get()
      set(value) {
         _k8sContainer.set(value)
      }

   private val _pod = AtomicReference<Pod>()
   protected var pod: Pod
      get() = _pod.get()
      set(value) {
         _pod.set(value)
      }

   private val _ipAddress = AtomicReference<InetAddress>()
   protected var ipAddress: InetAddress?
      get() = _ipAddress.get()
      set(value) {
         _ipAddress.set(value)
      }

   private val _startedAt = AtomicReference<Instant>()
   protected var startedAt: Instant?
      get() = _startedAt.get()
      set(value) {
         _startedAt.compareAndSet(null, value)
      }

   private val _finishedAt = AtomicReference<Instant>()
   protected var finishedAt: Instant?
      get() = _finishedAt.get()
      set(value) {
         _finishedAt.compareAndSet(null, value)
      }

   private val _exitCode = AtomicReference<Int>()
   protected var exitCode: Int?
      get() = _exitCode.get()
      set(value) {
         _exitCode.set(value)
      }

   protected val podPhaseLatches: Map<PodPhase, CountDownLatch> = mapOf(
      PodPhase.PENDING to CountDownLatch(1),
      PodPhase.RUNNING to CountDownLatch(1),
      PodPhase.FAILED to CountDownLatch(1),
      PodPhase.SUCCEEDED to CountDownLatch(1),
   )

   protected val podId = UUID.randomUUID().toString()

   protected val podLabels: Map<String, String> = mapOf(
      "app.kubernetes.io/instance" to podId,
   )

   protected val namespace: String
      get() = container.getNamespace().value

   protected val podName: String
      get() = pod.metadata?.name ?: throw IllegalStateException("No pod present")


   private var containerLogStreamer: ContainerLogStreamer? = null

   private val _execHandler = AtomicReference<ExecHandler>()
   private var execHandler: ExecHandler
      get() = _execHandler.get()
      set(value) {
         _execHandler.compareAndSet(null, value)
      }

   private val _fileTransferHandler = AtomicReference<FileTransferHandler>()
   private var fileTransferHandler: FileTransferHandler
      get() = _fileTransferHandler.get()
      set(value) {
         _fileTransferHandler.compareAndSet(null, value)
      }

   /**
    * Returns the name of the (first) Kubernetes container associated with the (first) pod.
    */
   override fun getName(): ContainerName {
      return k8sContainer.name?.let { ContainerName(it) } ?: throw IllegalStateException("No container present")
   }

   override fun start() {
      container.changeState(ContainerState.INITIALIZING, ContainerState.UNINITIATED)

      log.info("Starting container: ${container.getName()}")
      log.debug("Using container config$NEW_LINE${container.builder}")

      try {
         deploy()
         waitForPod()
      } catch (e: Exception) {
         container.changeState(ContainerState.FAILED)
         ErrorSupport.handleK8sException(e, log)
      }
   }

   override fun stop() {
      log.trace("Stopping container '${container.getName()}'")

      stopStreamingContainerLog()
      super.stop()
   }

   override fun delete(force: Boolean) {
      log.trace("Deleting container '${container.getName()}' with force: $force")

      if (force) {
         guardedExecution(
            { deleteResources() },
            listOf(Exception::class),
            { log.warn("Error stopping container '${container.getName()}': ${it.message}", it) }
         )
      } else {
         container.changeState(
            ContainerState.TERMINATING,
            ContainerState.RUNNING,
            ContainerState.STOPPED,
            ContainerState.FAILED
         )
         deleteResources()
      }

      finishedAt = Instant.now()

      stopStreamingContainerLog()

      val configMapsExists = client.configMaps()
         .inNamespace(namespace)
         .list()
         .items
         .any { item -> configMaps.any { backItem -> backItem.metadata.name == item.metadata.name } }

      if (configMapsExists) {
         configMaps.forEach { configMap ->
            guardedExecution(
               { client.configMaps().inNamespace(namespace).withName(configMap.metadata.name).delete() },
               listOf(Exception::class),
               { log.warn("Error deleting config map '${configMap.metadata.name}': ${it.message}", it) }
            )
         }
      }
      container.changeState(ContainerState.DELETED)
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
      refreshPod()

      return execHandler.execute(
         listOf(executable.unwrap()) + args?.toStringList().orEmpty(),
         useTty,
         input,
         output,
         waitTimeValue,
         waitTimeUnit
      )
   }

   override fun putFile(localFile: Path, remoteDir: UnixDir, remoteFilename: String?): Long {
      if (!Files.exists(localFile)) {
         throw FileNotFoundException("The local file '$localFile' does not exist")
      }
      refreshPod()
      return fileTransferHandler.putFile(localFile, remoteDir.unwrap(), remoteFilename)
   }

   override fun getFile(remoteDir: UnixDir, remoteFilename: String, localPath: Path?): Path {
      refreshPod()
      return fileTransferHandler.getFile(remoteDir.unwrap(), remoteFilename, localPath)
   }

   override fun putDirectory(localDir: Path, remoteDir: UnixDir): Long {
      require(localDir.exists() && localDir.isDirectory()) { "Local directory '$localDir' does not exist" }
      refreshPod()
      return fileTransferHandler.putDirectory(localDir, remoteDir.unwrap())
   }

   override fun getDirectory(remoteDir: UnixDir, localDir: Path): Pair<Path, List<Path>> {
      require(localDir.exists() && Files.isDirectory(localDir)) { "The provided path '$localDir' is not a directory." }
      refreshPod()
      return fileTransferHandler.getDirectory(remoteDir.unwrap(), localDir)
   }

   override fun getDuration(): Duration? {
      val start = _startedAt.get()
      if (start != null) {
         val end = _finishedAt.get() ?: Instant.now()
         return Duration.between(start, end)
      }
      return null
   }

   override fun getExitCode(): Int? {
      return exitCode
   }

   override fun getHost(): Host? {
      return host
   }

   override fun getIpAddress(): InetAddress? {
      return ipAddress
   }

   override fun getType(): ContainerPlatformType {
      return ContainerPlatformType.KUBERNETES
   }

   // Extra methods

//   fun getPodName(): String {
//      return podName
//   }

   protected fun createNamespaceIfAllowedAndNotExists() {
      if (!(accessChecker.canListNamespaces() and accessChecker.canCreateNamespaces())) {
         log.warn("Not allowed to list or create namespaces")
         return
      }

      val namespaceExists = client.namespaces().list().items.any { it.metadata.name == namespace }
      if (!namespaceExists) {
         val namespaceResource = NamespaceBuilder()
            .withNewMetadata()
            .withName(namespace)
            .endMetadata().build()

         client.namespaces()
            .resource(namespaceResource)
            .create().also {
               log.info("Created k8s namespace: $it")
            }
      }
   }

   protected abstract fun deploy()

   protected abstract fun configure(k8sContainer: Container)

   protected abstract fun configure(podSpec: PodSpec)

   protected abstract fun getResourceName(): String

   protected abstract fun deleteResources()

   protected fun getResourceMetaData(): ObjectMeta {
      return ObjectMeta().apply {
         name = getResourceName()
         namespace = this@K8sRuntime.namespace
         val stringLabels: Map<String, String> = container.getLabels().flatMap { (key, value) ->
            listOf(key.value to value.value)
         }.toMap()

         labels = stringLabels + createDefaultLabels()
      }
   }

   protected fun createPodTemplateSpec(): PodTemplateSpec {
      val k8sContainer = createK8sContainer()
      val (volumes, volumeMounts) = createVolumes()

      k8sContainer.volumeMounts = volumeMounts
      configure(k8sContainer)

      val podSpec = PodSpec().apply {
         containers = listOf(k8sContainer)
         this.volumes = volumes
      }

      configure(podSpec)

      val podTemplateMetadata = ObjectMeta().apply {
         name = container.getName().value + POD_NAME_SUFFIX
         this.labels = selectorLabels + createDefaultLabels() + podLabels
      }

      val podTemplateSpec = PodTemplateSpec().apply {
         metadata = podTemplateMetadata
         spec = podSpec
      }

      return podTemplateSpec
   }

   protected fun createDefaultLabels(): Map<String, String> {
      val defaultLabels: MutableMap<String, String> = mutableMapOf()

      val parentAppName = normalizeLabelValue(
         System.getProperty("spring.application.name")
            ?: "${System.getProperty("java.vm.name")}-${ProcessHandle.current().pid()}"
      )

      defaultLabels["app.kubernetes.io/managed-by"] = "Easycontainers"
      defaultLabels["app.kubernetes.io/timestamp"] = K8sUtils.instantToLabelValue(Instant.now())
      defaultLabels["app.kubernetes.io/part-of"] = parentAppName
      defaultLabels["app.kubernetes.io/ephemeral"] = container.isEphemeral().toString()

      container.getMaxLifeTime()?.let {
         defaultLabels["app.kubernetes.io/lifetime"] = it.toString()
      }

      if (K8sUtils.isRunningInsideCluster()) {
         defaultLabels["app.kubernetes.io/inside-cluster"] = "true"
         defaultLabels["app.kubernetes.io/parent-deployment"] = ourDeploymentName!!
      } else {
         defaultLabels["app.kubernetes.io/inside-cluster"] = "false"
      }

      return defaultLabels.toMap()
   }

   private fun waitForPod(maxWaitTimeSeconds: Long = 60L) {
      var pods: List<Pod> = emptyList()

      // Wait for it...
      try {
         Awaitility.await()
            .atMost(maxWaitTimeSeconds, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .until {
               pods = client
                  .pods()
                  .inNamespace(namespace)
                  .withLabels(podLabels)
                  .list()
                  .items

               pods.isNotEmpty()
            }
      } catch (e: ConditionTimeoutException) {
         val msg = "Timed out waiting for pod for ${container.getName()}"
         log.error(msg)
         throw ContainerException(msg)
      }

      // INVARIANT: There is at least one matching pod in the list

      if (pods.size > 1) {
         val msg = "More than one pod found when waiting for pod: ${container.getName()}"
         log.warn(msg)
         pods.forEach {
            log.info("Pod: ${it.prettyPrintMe()}")
         }
         throw ContainerException(msg)
      }

      // Set the pod once and for all
      pod = pods.first()

      // Set the container if present
      refreshContainer()

      ipAddress = InetAddress.getByName(pod.status.podIP)

      // Subscribe to pod events
      watchPod()

      // Stream logs to whatever output is configured
      startStreamingContainerLog()
   }

   private fun createK8sContainer(): Container {
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

   private fun createVolumes(): Pair<List<Volume>, List<VolumeMount>> {
      val volumes = mutableListOf<Volume>()
      val volumeMounts = mutableListOf<VolumeMount>()

      // Handle container files
      handleContainerFiles(volumes, volumeMounts)

      // Handle existing persistent volumes and claims
      handlePersistentVolumes(volumes, volumeMounts)

      // Handle memory-backed volumes
      handleMemoryBackedVolumes(volumes, volumeMounts)
      handleMemoryBackedVolumes(volumes, volumeMounts)

      return Pair(volumes, volumeMounts)
   }

   private fun watchPod() {
      val pods = client.pods()
         .inNamespace(namespace)
         .withLabels(podLabels)
         .list().items

      if (pods.size > 1) {
         throw ContainerException("Multiple matching pods found ${pods.size} for pod labels: $podLabels")
      }

      client.pods()
         .inNamespace(namespace)
         .withLabels(podLabels)
         .watch(PodWatcher())

      // Add a debug/logging watcher for the pod
      client.pods()
         .inNamespace(namespace)
         .withLabels(podLabels)
         .watch(LoggingWatcher())
   }

   private fun startStreamingContainerLog() {
      containerLogStreamer = ContainerLogStreamer(
         podName = podName,
         namespace = namespace,
         client = client,
         outputLineCallback = container.getOutputLineCallback()
      )

      // Start the log streamer in a separate (virtual) thread
      GENERAL_EXECUTOR_SERVICE.execute(containerLogStreamer!!)
   }

   private fun stopStreamingContainerLog() {
      containerLogStreamer?.stop()
   }

   private fun handleContainerFiles(
      volumes: MutableList<Volume>,
      volumeMounts: MutableList<VolumeMount>,
   ) {
      container.builder.containerFiles.forEach { (name, configFile) ->
         handleContainerFile(configFile, volumes, volumeMounts)
      }
   }

   private fun handleContainerFile(
      file: ContainerFile,
      volumes: MutableList<Volume>,
      volumeMounts: MutableList<VolumeMount>,
   ) {
      log.trace("Creating name -> config map mapping: ${file.name} -> $file")

      val baseName = "container-file-${file.name.value}"
      val configMapName = normalizeConfigMapName(
         "$baseName-${UUID.randomUUID().toString().truncate(5)}$CONFIG_MAP_NAME_SUFFIX}"
      )
      val volumeName = normalizeVolumeName("$baseName$VOLUME_NAME_SUFFIX")
      val fileName = file.name.value

      val configMap = createConfigMap(configMapName, fileName, file)
      applyConfigMap(configMap)

      val volume = createConfigMapVolume(volumeName, configMapName)
      volumes.add(volume)

      // Since we're using the same file name as the key, we don't need to specify the subPath
      val volumeMount = createVolumeMount(volumeName, file.mountPath.value)
      volumeMounts.add(volumeMount)
   }

   private fun createConfigMap(name: String, fileName: String, configFile: ContainerFile): ConfigMap {
      val configMap = ConfigMapBuilder()
         .withNewMetadata()
         .withName(name)
         .withNamespace(namespace)
         .addToLabels(createDefaultLabels())
         .endMetadata()
         .addToData(fileName, configFile.content) // fileName as the key
         .build()

      return configMap
   }

   private fun applyConfigMap(configMap: ConfigMap) {
      val configMapResource: Resource<ConfigMap> =
         client.configMaps()
            .inNamespace(namespace)
            .resource(configMap)

      configMapResource.get()?.let {
         configMapResource
            .withTimeout(30, TimeUnit.SECONDS)
            .delete()
            .also {
               log.info("Deleted existing k8s config map:$NEW_LINE${it.prettyPrintMe()}")
            }
      }

      configMapResource.create().also {
         configMaps.add(it)
         log.info("Created k8s config map:$NEW_LINE${it.prettyPrintMe()}")
         log.debug("ConfigMap YAML: ${Serialization.asYaml(configMap)}")
      }
   }

   private fun createConfigMapVolume(volumeName: String, configMapName: String): Volume {
      val volume = VolumeBuilder()
         .withName(volumeName)
         .withNewConfigMap()
         .withName(configMapName)
         .endConfigMap()
         .build()

      return volume
   }

   private fun createVolumeMount(name: String, mountPath: String, fileName: String? = null): VolumeMount {
      return VolumeMountBuilder()
         .withName(name)
         .withMountPath(mountPath)
         .apply {
            fileName?.let(this::withSubPath)
         }.build()
   }

   private fun handlePersistentVolumes(
      volumes: MutableList<Volume>,
      volumeMounts: MutableList<VolumeMount>,
   ) {
      container.builder.volumes
         .filter { !it.memoryBacked }
         .forEach { volume ->

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
               .withMountPath(volume.mountDir.value)
               .build()
            volumeMounts.add(volumeMount)

            log.info("Created persistent volume: ${volume.name}")
         }
   }

   private fun handleMemoryBackedVolumes(volumes: MutableList<Volume>, volumeMounts: MutableList<VolumeMount>) {
      container.builder.volumes
         .filter { it.memoryBacked }
         .forEach { volume ->
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
               .withMountPath(volume.mountDir.value)
               .build()
            volumeMounts.add(volumeMount)

            log.info("Created memory-backed volume: ${volume.name}")
         }
   }

   private fun extractOurDeploymentName(): String? {
      if (K8sUtils.isRunningOutsideCluster()) {
         return null
      }

      val podName = System.getenv(K8sConstants.ENV_HOSTNAME)  // Pod name from the HOSTNAME environment variable

      // Get the current pod based on the pod name and namespace
      val pod = client
         .pods()
         .inNamespace(namespace)
         .withName(podName)
         .get()

      // Get labels of the current pod
      val podLabels = pod.metadata.labels

      // Query all deployments in the namespace
      val deployments = client.apps().deployments()
         .inNamespace(namespace)
         .list().items

      // Find the deployment whose selector matches the pod's labels
      val deployment = deployments.firstOrNull { deployment ->
         deployment.spec.selector.matchLabels.entries.all {
            podLabels[it.key] == it.value
         }
      }

      return deployment?.metadata?.name ?: "unknown"
   }

   private fun refreshPod() {
      val refreshedPod = client
         .pods()
         .inNamespace(namespace)
         .withName(podName)
         .get()

      if (refreshedPod == null) {
         log.warn("Pod '$podName' not found in the cluster, refreshing failed.")
//         throw ContainerException("Pod '${pod.get().metadata.name}' not found in the cluster")
         return
      }

      pod = refreshedPod

      refreshContainer()
      updatePodState()
   }

   private fun refreshContainer() {
      val containers = pod.spec.containers

      if (containers.isEmpty()) {
         log.warn("No containers found in pod: $podName")
         return
      }

      if (containers.size > 1) {
         val msg = "More than one container (${containers.size}) found in pod '$podName'"
         log.warn(msg)
         containers.forEach {
            log.warn("Container: ${it.prettyPrintMe()}")
         }
         throw ContainerException(msg)
      }

      k8sContainer = containers.first()
      execHandler = ExecHandler(client, pod, k8sContainer)
      fileTransferHandler = FileTransferHandler(client, pod, k8sContainer)
   }

   private fun updatePodState() {
      // Determine the pod phase and update newState accordingly
      val podPhase = pod.status?.phase?.uppercase()?.let { phase ->
         PodPhase.valueOf(phase).also {
            log.debug("Current pod phase: $it")
         }
      }

      var newState = mapPodPhaseToContainerState(podPhase)
      val containerStatuses = pod.status?.containerStatuses

      handleContainerStatuses(containerStatuses, newState)?.also { updatedState ->
         newState = updatedState
      }

      // Apply the new state to the container object (assuming 'container' is accessible and represents the current container)
      if (container.isLegalStateChange(newState = newState)) {
         container.changeState(newState)
      } else {
         log.warn("Illegal state change attempt in container '${container.getName()}': ${container.getState()} -> $newState")
      }
   }

   private fun handleContainerStatuses(containerStatuses: List<ContainerStatus>?, newState: ContainerState): ContainerState? {
      if (containerStatuses.isNullOrEmpty()) {
         log.warn("No container statuses found in pod '$podName'")
         return null
      }

      if (containerStatuses.size > 1) {
         val msg = "More than one container status (${containerStatuses.size}) found in pod '$podName'"
         log.warn(msg)
         containerStatuses.forEach {
            log.warn("Container status: ${it.prettyPrintMe()}")
         }
         throw ContainerException(msg)
      }

      // Check the container status
      val containerStatus = containerStatuses.first()

      // Update start time if the container is running
      containerStatus.state.running?.startedAt?.let {
         startedAt = Instant.parse(it)
      }

      // Handle container termination information
      return handlePossibleTerminatedContainerState(containerStatus, newState)
   }

   private fun handlePossibleTerminatedContainerState(
      containerStatus: ContainerStatus,
      newState: ContainerState,
   ): ContainerState {
      var resultState = newState

      listOfNotNull(containerStatus.state.terminated, containerStatus.lastState.terminated)
         .firstOrNull()
         ?.let { terminatedState ->
            resultState = handleTerminatedContainer(containerStatus, terminatedState)
         }

      return resultState
   }

   private fun handleTerminatedContainer(
      containerStatus: ContainerStatus,
      terminatedState: ContainerStateTerminated,
   ): ContainerState {
      log.info(
         "Container ${containerStatus.name} terminated with signal '${terminatedState.signal}', " +
            "reason: ${terminatedState.reason}, message: '${terminatedState.message}'"
      )

      // Update finish time and exit code upon termination
      terminatedState.finishedAt?.let {
         finishedAt = Instant.parse(it)
      }

      terminatedState.exitCode?.let { code ->
         exitCode = code.also {
            log.info("Container '${getName()}' exited with code: $code")
         }
      }

      // Check if the container was terminated due to an error
      return if (terminatedState.reason.lowercase().contains("error")) {
         log.warn(
            "Container '${containerStatus.name}' terminated due to '" +
               " ${terminatedState.reason}': ${terminatedState.message}"
         )
         ContainerState.FAILED

      } else {
         // When a container is terminated, we consider the pod to be STOPPED
         ContainerState.STOPPED
      }
   }


}
