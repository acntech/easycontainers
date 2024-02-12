package no.acntech.easycontainers.k8s

import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec
import io.fabric8.kubernetes.client.*
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.utils.Serialization
import no.acntech.easycontainers.AbstractContainer
import no.acntech.easycontainers.ContainerBuilder
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.k8s.K8sConstants.ENV_HOSTNAME
import no.acntech.easycontainers.k8s.K8sConstants.MEDIUM_MEMORY_BACKED
import no.acntech.easycontainers.k8s.K8sUtils.normalizeLabelValue
import no.acntech.easycontainers.model.Host
import no.acntech.easycontainers.util.text.NEW_LINE
import no.acntech.easycontainers.util.text.SPACE
import org.awaitility.Awaitility.await
import java.io.File
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference


internal class K8sContainer(
   builder: ContainerBuilder,
   private val client: KubernetesClient = K8sClientFactory.createDefaultClient()
) : AbstractContainer(builder) {

   private inner class PodWatcher : Watcher<Pod> {

      override fun eventReceived(action: Watcher.Action?, resource: Pod?) {
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

      // Used to schedule the stopAndRemoveTask when a timeout is set
      var SCHEDULER: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
   }

   private var deployment: Deployment? = null

   private var service: Service? = null

   private val accessChecker = AccessChecker(client)

   private val pods: MutableList<Pair<Pod, List<Container>>> = CopyOnWriteArrayList()

   private val configMaps: MutableList<ConfigMap> = mutableListOf()

   private var ourDeploymentName: String?

   private val selectorLabels: Map<String, String> = mapOf(K8sConstants.APP_LABEL to getName().value)

   private var containerLogStreamer: ContainerLogStreamer? = null

   private var host: Host? = null

   private val ipAddress: AtomicReference<InetAddress> = AtomicReference()

   private val loggingExecutorService = Executors.newVirtualThreadPerTaskExecutor()

   init {
      createDeployment()
      if (builder.exposedPorts.isNotEmpty()) {
         createService(isNodePort = K8sUtils.isRunningOutsideCluster())
      }
      ourDeploymentName = getOurDeploymentName()
   }

   @Synchronized
   override fun run() {
      requireState(no.acntech.easycontainers.model.Container.State.CREATED)

      log.info("Starting container: ${getName()}")
      log.debug("Using container config\n${builder}")

      try {
         createNamespaceIfAllowedAndNotExists()
         deleteServiceIfExists()
         deleteDeploymentIfExists()

         deployment = client.apps().deployments().inNamespace(getNamespace().value).resource(deployment).create().also {
            log.info("Deployed k8s deployment: $it")
         }

         service?.let {
            service = client.services().inNamespace(getNamespace().value).resource(service).create().also {
               log.info("Deployed k8s service: $it")
            }
         }

         extractPodsAndContainers()

         // INVARIANT: podsBackingField/pods is not empty

         builder.maxLifeTime?.let {
            SCHEDULER.schedule(KillTask(), it.toSeconds(), TimeUnit.SECONDS)
         }

         client.pods().inNamespace(getNamespace().value).withLabels(selectorLabels).watch(PodWatcher())

         containerLogStreamer = ContainerLogStreamer(
            this.pods.first().first.metadata.name,
            getNamespace().value,
            client,
            builder.outputLineCallback
         )

      } catch (e: Exception) {
         k8sError(e)
      }

      service?.let {
         val serviceName = service!!.metadata.name
         val namespace = service!!.metadata.namespace
         host = Host.of("$serviceName.$namespace.svc.cluster.local").also {
            log.info("Container (service) host: $it")
         }
      }

      loggingExecutorService.execute(containerLogStreamer!!)

      // We have officially started the container(s)
      changeState(no.acntech.easycontainers.model.Container.State.STARTED)
   }

   override fun waitForCompletion(timeoutValue: Long, timeoutUnit: TimeUnit): Int {
      TODO("Not yet implemented")
   }

   @Synchronized
   override fun stop() {
      requireState(
         no.acntech.easycontainers.model.Container.State.CREATED,
         no.acntech.easycontainers.model.Container.State.STARTED,
         no.acntech.easycontainers.model.Container.State.RUNNING,
         no.acntech.easycontainers.model.Container.State.UNKNOWN
      )

      val exists = client.apps()
         .deployments()
         .inNamespace(getNamespace().value)
         .withName(getName().value + DEPLOYMENT_NAME_SUFFIX)
         .get() != null

      if (exists) {
         // Scale only if the deployment is found
         client.apps()
            .deployments()
            .inNamespace(getNamespace().value)
            .withName(getName().value + DEPLOYMENT_NAME_SUFFIX)
            .scale(0)

         changeState(no.acntech.easycontainers.model.Container.State.STOPPED)
      } else {
         // Handle the case where the deployment is not found
         log.warn("Deployment to stop not found: ${getName().value + DEPLOYMENT_NAME_SUFFIX}")
      }

      containerLogStreamer?.stop()
   }

   @Synchronized
   override fun remove() {
      requireState(
         no.acntech.easycontainers.model.Container.State.STOPPED,
         no.acntech.easycontainers.model.Container.State.FAILED
      )

      deleteServiceIfExists()
      deleteDeploymentIfExists()

      val configMapsExists = client.configMaps()
         .inNamespace(getNamespace().value)
         .list()
         .items
         .any { item -> configMaps.any { backItem -> backItem.metadata.name == item.metadata.name } }

      if (configMapsExists) {
         configMaps.forEach { configMap ->
            try {
               client.configMaps()
                  .inNamespace(getNamespace().value)
                  .withName(configMap.metadata.name)
                  .delete()
            } catch (e: Exception) {
               log.error("Error while deleting config map: ${configMap.metadata.name}", e)
            }
         }
      }

      loggingExecutorService.shutdownNow()

      changeState(no.acntech.easycontainers.model.Container.State.REMOVED)
   }

   override fun getHost(): Host? {
      return host
   }

   override fun getIpAddress(): InetAddress? {
      return ipAddress.get()
   }

   override fun getDuration(): Duration? {
      return null
   }

   @Throws(ContainerException::class)
   private fun k8sError(e: Exception) {
      when (e) {
         is KubernetesClientException,
         is ResourceNotFoundException,
         is WatcherException,
         -> {
            val message = "Error starting container: ${e.message}"
            log.error(message, e)
            throw ContainerException(message, e)
         }

         else -> {
            log.error("Unexpected exception '${e.javaClass.simpleName}:'${e.message}', re-throwing", e)
            throw e // Rethrow the exception if it's not one of the handled types
         }
      }
   }

   @Synchronized
   private fun refreshPodsAndCheckStatus() {
      log.trace("Refreshing pods and checking status")

      val refreshedPods = mutableListOf<Pair<Pod, List<Container>>>()

      pods.forEach { (pod, containers) ->
         val refreshedPod = client.pods()
            .inNamespace(getNamespace().value)
            .withName(pod.metadata.name)
            .get()

         if (refreshedPod != null) {
            log.debug("Pod refreshed: ${refreshedPod.metadata.name} - current phase: ${refreshedPod.status?.phase}")

            val newState = when (refreshedPod.status?.phase) {
               "Running" -> no.acntech.easycontainers.model.Container.State.RUNNING
               "Pending" -> no.acntech.easycontainers.model.Container.State.STARTED
               "Failed" -> no.acntech.easycontainers.model.Container.State.FAILED
               "Succeeded" -> no.acntech.easycontainers.model.Container.State.STOPPED
               else -> no.acntech.easycontainers.model.Container.State.UNKNOWN
            }

            changeState(newState)

            refreshedPods.add(Pair(refreshedPod, containers))
         }
      }

      pods.clear()
      pods.addAll(refreshedPods)

      // Extract the IP address of the first pod in the list (most likely the only one)
      ipAddress.set(InetAddress.getByName(pods.first().first.status.podIP))

      // Make a succinct string representation of the pods list
      val stringVal = this.pods.joinToString(", ") { (pod, container) ->
         pod.metadata.name + " -> " + container.joinToString { it.name }
      }

      log.trace("Pods and containers refreshed: $stringVal")
   }

   private fun extractPodsAndContainers(maxWaitTimeSeconds: Long = 30L) {
      var pods: List<Pod> = emptyList()

      await().atMost(maxWaitTimeSeconds, TimeUnit.SECONDS).until {
         pods = client.pods().inNamespace(getNamespace().value).withLabels(selectorLabels).list().items
         pods.isNotEmpty()
      }

      if (pods.isEmpty()) {
         throw ContainerException(
            "Timer expired ($maxWaitTimeSeconds seconds) waiting for pods for service:" +
               " ${service!!.metadata.name}"
         )
      }

      // INVARIANT: pods is not empty
      if (pods.size > 1) {
         log.warn("Multiple pods found for service: ${service!!.metadata.name}")
      }

      // Get the IP address of the first pod in the list (most likely the only one)
      ipAddress.set(InetAddress.getByName(pods.first().status.podIP))

      // Add a debug/logging watcher for the pods
      client.pods().inNamespace(getNamespace().value).withLabels(selectorLabels).watch(LoggingWatcher())

      // Pair each pod with its list of containers
      pods.forEach { pod ->
         this.pods.add(Pair(pod, pod.spec.containers))
      }

      this.pods.forEach { (pod, containers) ->
         log.info("Pod: ${pod.metadata.name}")
         containers.forEach { container ->
            log.info("Container: ${container.name}")
         }
      }
   }

   private fun createNamespaceIfAllowedAndNotExists() {
      if (!(accessChecker.canListNamespaces() and accessChecker.canCreateNamespaces())) {
         log.warn("Not allowed to list or create namespaces")
         return
      }

      val namespaceExists = client.namespaces().list().items.any { it.metadata.name == getNamespace().value }
      if (!namespaceExists) {
         val namespaceResource = NamespaceBuilder().withNewMetadata().withName(getNamespace().value).endMetadata().build()
         client.namespaces().resource(namespaceResource).create().also {
            log.info("Created k8s namespace: $it")
         }
      }
   }

   private fun createDeployment() {
      val container = createContainer()
      val (volumes, volumeMounts) = createVolumes()
      container.volumeMounts = volumeMounts

      val containerPort = getExposedPorts().firstOrNull()?.value

      containerPort?.let {
         log.info("Exposed port found for container '${getName()}':$it - creating probes for liveness and readiness")
      }

      val (livenessProbe, readinessProbe) = createProbes(containerPort)
      container.livenessProbe = livenessProbe
      container.readinessProbe = readinessProbe

      val podSpec = createPodSpec(container, volumes)

      // Create the deployment
      createDeploymentFromPodSpec(podSpec)
   }

   private fun createContainer(): Container {
      val requests = mutableMapOf<String, Quantity>()
      val limits = mutableMapOf<String, Quantity>()

      builder.cpuRequest?.let {
         requests["cpu"] = Quantity(it.toString())
      }

      builder.memoryRequest?.let {
         requests["memory"] = Quantity(it.toString())
      }

      builder.cpuLimit?.let {
         limits["cpu"] = Quantity(it.toString())
      }

      builder.memoryLimit?.let {
         limits["memory"] = Quantity(it.toString())
      }

      val resourceRequirements = ResourceRequirements()
      resourceRequirements.requests = requests
      resourceRequirements.limits = limits

      return Container().apply {
         val k8sContainer = this@K8sContainer
         name = "${k8sContainer.getName().value}$CONTAINER_NAME_SUFFIX"
         image = k8sContainer.getImage().toFQDN()
         env = k8sContainer.getEnv().map { EnvVar(it.key.value, it.value.value, null) }
         ports = k8sContainer.getExposedPorts().map {
            ContainerPort(it.value, null, null, null, "TCP")
         }
         k8sContainer.getCommand()?.let {
            command = it.value.split(SPACE)
         }
         k8sContainer.getArgs()?.let {
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

      return Pair(volumes, volumeMounts)
   }

   private fun handleContainerFiles(
      volumes: MutableList<Volume>,
      volumeMounts: MutableList<VolumeMount>,
   ) {
      builder.containerFiles.forEach { (name, configFile) ->
         log.trace("Creating name -> config map mapping: $name -> $configFile")

         // Extract directory and filename from mountPath, ensuring forward slashes
         val mountPath = File(configFile.mountPath.value).parent?.replace("\\", "/") ?: "/"
         val fileName = File(configFile.mountPath.value).name

         // Create a ConfigMap object with a single entry
         val configMap = ConfigMapBuilder()
            .withNewMetadata()
            .withName(name.value)
            .withNamespace(getNamespace().value)
            .addToLabels(createDefaultLabels())
            .endMetadata()
            .addToData(fileName, configFile.content) // fileName as the key
            .build()

         val configMapResource: Resource<ConfigMap> = client.configMaps().inNamespace(getNamespace().value).resource(configMap)

         if (configMapResource.get() != null) {
            configMapResource.withTimeout(30, TimeUnit.SECONDS).delete().also {
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
      builder.volumes.filter { !it.memoryBacked }.forEach { volume ->

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
      builder.volumes.filter { it.memoryBacked }.forEach { volume ->
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

   private fun createProbes(port: Int?): Pair<Probe, Probe> {
      val livenessProbe = Probe().apply {
         if (port != null) {
            tcpSocket = TCPSocketAction(null, IntOrString(port))
         } else {
            exec = ExecAction(listOf("echo", "alive"))
         }
         initialDelaySeconds = 10
         periodSeconds = 5
      }.also {
         log.info("Liveness probe created: $it")
      }

      val readinessProbe = Probe().apply {
         if (port != null) {
            tcpSocket = TCPSocketAction(null, IntOrString(port))
         } else {
            exec = ExecAction(listOf("echo", "ready"))
         }
         initialDelaySeconds = 5
         periodSeconds = 5
      }.also {
         log.info("Readiness probe created: $it")
      }

      return Pair(livenessProbe, readinessProbe)
   }


   private fun createDefaultLabels(): Map<String, String> {
      val defaultLabels: MutableMap<String, String> = mutableMapOf()

      val parentAppName = normalizeLabelValue(
         System.getProperty("spring.application.name")
            ?: "${System.getProperty("java.vm.name")}-${ProcessHandle.current().pid()}"
      )

      defaultLabels["acntech.no/created-by"] = "Easycontainers"
      defaultLabels["easycontainers.acntech.no/parent-application"] = parentAppName
      defaultLabels["easycontainers.acntech.no/created-at"] = K8sUtils.instantToLabelValue(Instant.now())
      defaultLabels["easycontainers.acntech.no/is-ephemeral"] = builder.isEphemeral.toString()
      if (builder.maxLifeTime != null) {
         defaultLabels["easycontainers.acntech.no/max-life-time"] = builder.maxLifeTime.toString()
      }

      if (K8sUtils.isRunningInsideCluster()) {
         defaultLabels["easycontainers.acntech.no/parent-running-inside-cluster"] = "true"
         defaultLabels["easycontainers.acntech.no/parent-deployment"] = ourDeploymentName!!
      } else {
         defaultLabels["easycontainers.acntech.no/parent-running-inside-cluster"] = "false"
      }

      return defaultLabels.toMap()
   }

   private fun createPodSpec(container: Container, volumes: List<Volume>): PodSpec {
      return PodSpec().apply {
         containers = listOf(container)
         this.volumes = volumes
      }
   }

   private fun createDeploymentFromPodSpec(podSpec: PodSpec) {
      val templateMetadata = ObjectMeta().apply {
         this.labels = selectorLabels + createDefaultLabels()
         name = this@K8sContainer.getName().value + POD_NAME_SUFFIX
      }

      val podTemplateSpec = PodTemplateSpec().apply {
         metadata = templateMetadata
         spec = podSpec
      }

      val deploymentSpec = DeploymentSpec().apply {
         replicas = 1
         template = podTemplateSpec
         selector = LabelSelector(null, selectorLabels)
      }

      val deploymentMetadata = ObjectMeta().apply {
         name = this@K8sContainer.getName().value + DEPLOYMENT_NAME_SUFFIX
         namespace = this@K8sContainer.getNamespace().value
         val stringLabels: Map<String, String> = this@K8sContainer.getLabels().flatMap { (key, value) ->
            listOf(key.value to value.value)
         }.toMap()

         labels = stringLabels + createDefaultLabels()
      }

      deployment = Deployment().apply {
         apiVersion = "apps/v1"
         kind = "Deployment"
         metadata = deploymentMetadata
         spec = deploymentSpec
      }.also {
         log.debug("Created deployment: $it")
         log.info("Deployment YAML:$NEW_LINE${Serialization.asYaml(it)}")
      }
   }

   private fun createService(isNodePort: Boolean = false) {
      // Manually creating the service metadata
      val serviceMetadata = ObjectMeta()
      serviceMetadata.name = getName().value + SERVICE_NAME_SUFFIX
      serviceMetadata.namespace = getNamespace().value
      serviceMetadata.labels = createDefaultLabels()

      // Manually creating the service ports
      val servicePorts = builder.exposedPorts.map { (name, internalPort) ->
         ServicePortBuilder()
            .withName(name.value)
            .withPort(internalPort.value)
            .withNewTargetPort(internalPort.value)
            .apply {
               if (isNodePort && hasPortMapping(internalPort)) {
                  val nodePort = getMappedPort(internalPort).value
                  if (nodePort !in K8sConstants.NODE_PORT_RANGE_START..K8sConstants.NODE_PORT_RANGE_END) {
                     log.warn(
                        "The mapped NodePort $nodePort for internal port $internalPort " +
                           "is outside the recommended range " +
                           "(${K8sConstants.NODE_PORT_RANGE_START}-${K8sConstants.NODE_PORT_RANGE_END})"
                     )
                  }
                  withNodePort(nodePort)
               }
            }.build()
      }

      // Manually creating the service spec
      val serviceSpec = ServiceSpec().apply {
         this.selector = selectorLabels
         this.ports = servicePorts
         this.type = if (isNodePort) K8sConstants.NODE_PORT_DIRECTIVE else K8sConstants.CLUSTER_IP_DIRECTIVE
      }

      // Creating the service object
      service = Service().apply {
         apiVersion = "v1"
         kind = "Service"
         metadata = serviceMetadata
         spec = serviceSpec
      }.also {
         log.debug("Created service: $it")
         log.info("Service YAML:$NEW_LINE${Serialization.asYaml(it)}")
      }
   }

   private fun deleteDeploymentIfExists() {
      val namespaceName = getNamespace().value
      val deploymentName = getName().value + DEPLOYMENT_NAME_SUFFIX
      val deployment = client.apps().deployments().inNamespace(namespaceName).withName(deploymentName).get()
      if (deployment != null) {
         log.info("Deleting deployment: $deploymentName")
         client.apps().deployments().inNamespace(namespaceName).withName(deploymentName).delete()

         // Wait for the deployment to be removed
         await().atMost(30, TimeUnit.SECONDS).until {
            client.apps().deployments().inNamespace(namespaceName).withName(deploymentName).get() == null
         }
         log.info("Deployment deleted: $deploymentName")
      } else {
         log.trace("Deployment does not exist: $deploymentName")
      }
   }

   private fun deleteServiceIfExists() {
      val namespaceName = getNamespace().value
      val serviceName = getName().value + SERVICE_NAME_SUFFIX

      client.services().inNamespace(namespaceName).withName(serviceName).get()?.let {
         log.info("Deleting service: $serviceName")

         client.services().inNamespace(namespaceName).withName(serviceName).delete()

         // Wait for the service to be removed
         await().atMost(30, TimeUnit.SECONDS).until {
            client.services().inNamespace(namespaceName).withName(serviceName).get() == null
         }

         log.info("Service successfully deleted: $serviceName")

      } ?: log.trace("Service does not exist - nothing to delete: $serviceName")
   }

   private fun getOurDeploymentName(): String? {
      if (K8sUtils.isRunningOutsideCluster()) {
         return null
      }

      val podName = System.getenv(ENV_HOSTNAME)  // Pod name from the HOSTNAME environment variable

      // Get the current pod based on the pod name and namespace
      val pod = client.pods().inNamespace(getNamespace().value).withName(podName).get()

      // Get labels of the current pod
      val podLabels = pod.metadata.labels

      // Query all deployments in the namespace
      val deployments = client.apps().deployments().inNamespace(getNamespace().value).list().items

      // Find the deployment whose selector matches the pod's labels
      val myDeployment = deployments.firstOrNull { deployment ->
         deployment.spec.selector.matchLabels.entries.all {
            podLabels[it.key] == it.value
         }
      }

      return myDeployment?.metadata?.name ?: "unknown"
   }

}