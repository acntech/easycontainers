package no.acntech.easycontainers.k8s

import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.utils.Serialization
import no.acntech.easycontainers.AbstractContainer
import no.acntech.easycontainers.ContainerBuilder
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.k8s.K8sConstants.CORE_API_GROUP
import no.acntech.easycontainers.k8s.K8sUtils.normalizeLabelValue
import no.acntech.easycontainers.util.text.NEW_LINE
import no.acntech.easycontainers.util.text.SPACE
import org.awaitility.Awaitility.await
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


internal class K8sContainer(
   builder: ContainerBuilder,
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

      var scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
   }

   private var deployment: Deployment? = null

   private var service: Service? = null


   private var client: KubernetesClient = KubernetesClientBuilder().build()

   private val pods: MutableList<Pair<Pod, List<Container>>> = mutableListOf()

   private val configMaps: MutableList<ConfigMap> = mutableListOf()

   private var ourDeploymentName: String?

   private val selectorLabels: Map<String, String> = mapOf(K8sConstants.APP_LABEL to getName())

   private var containerLogStreamer: ContainerLogStreamer? = null

   private val stopAndRemoveTask = Runnable {
      stop()
      remove()
   }

   private val executorService = Executors.newVirtualThreadPerTaskExecutor()

   init {
      createDeployment()
      createService(isNodePort = K8sUtils.isRunningOutsideCluster())
      ourDeploymentName = getOurDeploymentName()
   }

   @Synchronized
   override fun start() {
      requireState(no.acntech.easycontainers.Container.State.CREATED)

      createNamespaceIfAllowedAndNotExists()
      deleteServiceIfExists()
      deleteDeploymentIfExists()

      deployment = client.apps().deployments().inNamespace(getNamespace()).resource(deployment).create().also {
         log.info("Deployed k8s deployment: $it")
      }

      service = client.services().inNamespace(getNamespace()).resource(service).create().also {
         log.info("Deployed k8s service: $it")
      }

      extractPodsAndContainers()

      // INVARIANT: podsBackingField/pods is not empty

      builder.maxLifeTime?.let {
         scheduler.schedule(stopAndRemoveTask, it.toSeconds(), TimeUnit.SECONDS)
      }

      client.pods().inNamespace(getNamespace()).withLabels(selectorLabels).watch(PodWatcher())

      containerLogStreamer = ContainerLogStreamer(
         this.pods.first().first.metadata.name,
         getNamespace()!!,
         client,
         builder.lineCallback
      )

      val serviceName = service!!.metadata.name
      val namespace = service!!.metadata.namespace

      internalHost = "$serviceName.$namespace.svc.cluster.local"
      log.info("Container (service) host: $internalHost")

      executorService.execute(containerLogStreamer!!)

      // We have officially started the container(s)
      changeState(no.acntech.easycontainers.Container.State.STARTED)
   }

   @Synchronized
   override fun stop() {
      requireState(
         no.acntech.easycontainers.Container.State.CREATED,
         no.acntech.easycontainers.Container.State.STARTED,
         no.acntech.easycontainers.Container.State.RUNNING,
         no.acntech.easycontainers.Container.State.UNKNOWN
      )

      val exists = client.apps()
         .deployments()
         .inNamespace(getNamespace())
         .withName(getName() + DEPLOYMENT_NAME_SUFFIX)
         .get() != null

      if (exists) {
         // Scale only if the deployment is found
         client.apps()
            .deployments()
            .inNamespace(getNamespace())
            .withName(getName() + DEPLOYMENT_NAME_SUFFIX)
            .scale(0)

         changeState(no.acntech.easycontainers.Container.State.STOPPED)
      } else {
         // Handle the case where the deployment is not found
         log.warn("Deployment to stop not found: ${getName() + DEPLOYMENT_NAME_SUFFIX}")
      }

      containerLogStreamer?.stop()
   }

   @Synchronized
   override fun remove() {
      requireState(
         no.acntech.easycontainers.Container.State.STOPPED,
         no.acntech.easycontainers.Container.State.FAILED
      )

      deleteServiceIfExists()
      deleteDeploymentIfExists()

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
               log.error("Error while deleting config map: ${configMap.metadata.name}", e)
            }
         }
      }

      executorService.shutdownNow()

      changeState(no.acntech.easycontainers.Container.State.REMOVED)
   }

   @Synchronized
   private fun refreshPodsAndCheckStatus() {
      log.trace("Refreshing pods and checking status")

      val refreshedPods = mutableListOf<Pair<Pod, List<Container>>>()

      this.pods.forEach { (pod, containers) ->
         val refreshedPod = client.pods()
            .inNamespace(getNamespace())
            .withName(pod.metadata.name)
            .get()

         if (refreshedPod != null) {
            log.debug("Pod refreshed: ${refreshedPod.metadata.name} - current phase: ${refreshedPod.status?.phase}")

            val newState = when (refreshedPod.status?.phase) {
               "Running" -> no.acntech.easycontainers.Container.State.RUNNING
               "Pending" -> no.acntech.easycontainers.Container.State.STARTED
               "Failed" -> no.acntech.easycontainers.Container.State.FAILED
               "Succeeded" -> no.acntech.easycontainers.Container.State.STOPPED
               else -> no.acntech.easycontainers.Container.State.UNKNOWN
            }

            changeState(newState)

            refreshedPods.add(Pair(refreshedPod, containers))
         }
      }

      // Remove the existing pairs from podsBackingField
      this.pods.removeIf { (pod, _) ->
         refreshedPods.any { (refreshedPod, _) -> refreshedPod.metadata.name == pod.metadata.name }
      }

      // Add the refreshed pairs to podsBackingField
      this.pods.addAll(refreshedPods)

      log.trace("Pods and containers refreshed: ${this.pods}")
   }

   private fun extractPodsAndContainers(maxWaitTimeSeconds: Long = 30L) {
      var pods: List<Pod> = emptyList()

      await().atMost(maxWaitTimeSeconds, TimeUnit.SECONDS).until {
         pods = client.pods().inNamespace(getNamespace()).withLabels(selectorLabels).list().items
         pods.isNotEmpty()
      }

      if (pods.isEmpty()) {
         throw ContainerException(
            "Timer expired ($maxWaitTimeSeconds seconds) waiting for pods for service:" +
               " ${service!!.metadata.name}"
         )
      }

      // INVARIANT: pods is not empty

      // Add a debug/logging watcher for the pods
      client.pods().inNamespace(getNamespace()).withLabels(selectorLabels).watch(LoggingWatcher())

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
      if (!canListNamespaces()) {
         log.warn("Not allowed to list namespaces")
         return
      }

      val namespace = getNamespace()
      val namespaceExists = client.namespaces().list().items.any { it.metadata.name == namespace }
      if (!namespaceExists) {
         val namespaceResource = NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build()
         client.namespaces().resource(namespaceResource).create().also {
            log.info("Created k8s namespace: $it")
         }
      }
   }

   private fun canListNamespaces(): Boolean {
      val review = SelfSubjectAccessReviewBuilder()
         .withNewSpec()
         .withNewResourceAttributes()
         .withGroup(CORE_API_GROUP)
         .withResource("namespaces")
         .withVerb("list")
         .endResourceAttributes()
         .endSpec()
         .build()

      val response = client.authorization().v1().selfSubjectAccessReview().create(review)
      return response.status?.allowed ?: false
   }

   private fun createDeployment() {
      val container = createContainer()
      val (volumes, volumeMounts) = createVolumes()
      container.volumeMounts = volumeMounts

      val containerPort = getExposedPorts().firstOrNull()?.toLong()?.toInt()

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
         name = this@K8sContainer.getName() + CONTAINER_NAME_SUFFIX
         image = this@K8sContainer.getImage()
         env = this@K8sContainer.getEnv().map { EnvVar(it.key, it.value, null) }
         ports = getExposedPorts().map {
            ContainerPort(it.toLong().toInt(), null, null, null, "TCP")
         }

         if (this@K8sContainer.getCommand().isNotBlank()) {
            command = this@K8sContainer.getCommand().split(SPACE)
         }

         if (this@K8sContainer.getArgs().isNotEmpty()) {
            args = this@K8sContainer.getArgs()
         }

         resources = resourceRequirements
      }
   }

   private fun createVolumes(): Pair<List<Volume>, List<VolumeMount>> {
      val volumes = mutableListOf<Volume>()
      val volumeMounts = mutableListOf<VolumeMount>()

      // First create the ConfigMaps
      builder.configFiles.forEach { (name, configFile) ->
         log.trace("Creating name -> config map mapping: $name -> $configFile")

         // Extract directory and filename from mountPath, ensuring forward slashes
         val mountPath = File(configFile.mountPath).parent?.replace("\\", "/") ?: "/"
         val fileName = File(configFile.mountPath).name

         // Create a ConfigMap object with a single entry
         val configMap = ConfigMapBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(getNamespace())
            .addToLabels(createDefaultLabels())
            .endMetadata()
            .addToData(fileName, configFile.content) // fileName as the key
            .build()

         val configMapResource: Resource<ConfigMap> = client.configMaps().inNamespace(getNamespace()).resource(configMap)
         if (configMapResource.get() != null) {
            configMapResource.withTimeout(30, TimeUnit.SECONDS).delete().also {
               log.info("Deleted existing k8s config map: $it")
            }
         }

         configMapResource.create().also {
            configMaps.add(it)
            log.info("Created k8s config map: $it")
            val configMapYaml = Serialization.asYaml(configMap)
            log.debug("ConfigMap YAML: $configMapYaml")
         }

         // Create the corresponding Volume
         val volume = VolumeBuilder()
            .withName(name)
            .withNewConfigMap()
            .withName(name)
            .endConfigMap()
            .build()
         volumes.add(volume)

         // Create the corresponding VolumeMount
         val volumeMount = VolumeMountBuilder()
            .withName(name)
            .withMountPath(mountPath)
            .withSubPath(fileName) // Mount only the specific file within the directory
            .build()
         volumeMounts.add(volumeMount)
      }

      // Map existing Persistent Volumes and Claims
      builder.volumes.values.forEach { volume ->
         // Derive the PVC name from the volume name
         val pvcName = "${volume.name}$PVC_NAME_SUFFIX"

         // Create the Volume using the existing PVC
         val k8sVolume = VolumeBuilder()
            .withName(volume.name)
            .withNewPersistentVolumeClaim()
            .withClaimName(pvcName)
            .endPersistentVolumeClaim()
            .build()
         volumes.add(k8sVolume)

         // Create the VolumeMount
         val volumeMount = VolumeMountBuilder()
            .withName(volume.name)
            .withMountPath(volume.mountPath)
            .build()
         volumeMounts.add(volumeMount)
      }

      return Pair(volumes, volumeMounts)
   }

   private fun createProbes(port: Int?): Pair<Probe, Probe> {
      val livenessProbe = Probe().apply {
         tcpSocket = TCPSocketAction(null, IntOrString(port))
         initialDelaySeconds = 10
         periodSeconds = 5
      }

      val readinessProbe = Probe().apply {
         tcpSocket = TCPSocketAction(null, IntOrString(port))
         initialDelaySeconds = 5
         periodSeconds = 5
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
         name = this@K8sContainer.getName() + POD_NAME_SUFFIX
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
         name = this@K8sContainer.getName() + DEPLOYMENT_NAME_SUFFIX
         namespace = this@K8sContainer.getNamespace()
         this.labels = this@K8sContainer.getLabels() + createDefaultLabels()
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
      serviceMetadata.name = getName() + SERVICE_NAME_SUFFIX
      serviceMetadata.namespace = getNamespace()
      serviceMetadata.labels = createDefaultLabels()

      // Manually creating the service ports
      val servicePorts = builder.exposedPorts.map { (name, internalPort) ->
         ServicePortBuilder()
            .withName(name)
            .withPort(internalPort)
            .withNewTargetPort(internalPort)
            .apply {
               if (isNodePort && hasPortMapping(internalPort)) {
                  val nodePort = getMappedPort(internalPort)
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
      val namespaceName = getNamespace()
      val deploymentName = getName() + DEPLOYMENT_NAME_SUFFIX
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
      val namespaceName = getNamespace()
      val serviceName = getName() + SERVICE_NAME_SUFFIX
      val service = client.services().inNamespace(namespaceName).withName(serviceName).get()
      if (service != null) {
         log.info("Deleting service: $serviceName")
         client.services().inNamespace(namespaceName).withName(serviceName).delete()

         // Wait for the service to be removed
         await().atMost(30, TimeUnit.SECONDS).until {
            client.services().inNamespace(namespaceName).withName(serviceName).get() == null
         }
         log.info("Service deleted: $serviceName")
      } else {
         log.trace("Service does not exist: $serviceName")
      }
   }

   private fun getOurDeploymentName(): String? {
      if (K8sUtils.isRunningOutsideCluster()) {
         return null
      }

      val podName = System.getenv("HOSTNAME")  // Pod name from the HOSTNAME environment variable

      // Get the current pod based on the pod name and namespace
      val pod = client.pods().inNamespace(getNamespace()).withName(podName).get()

      // Get labels of the current pod
      val podLabels = pod.metadata.labels

      // Query all deployments in the namespace
      val deployments = client.apps().deployments().inNamespace(getNamespace()).list().items

      // Find the deployment whose selector matches the pod's labels
      val myDeployment = deployments.firstOrNull { deployment ->
         deployment.spec.selector.matchLabels.entries.all {
            podLabels[it.key] == it.value
         }
      }

      return myDeployment?.metadata?.name ?: "unknown"
   }

}