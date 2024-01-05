package no.acntech.easycontainers.k8s

import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.utils.Serialization
import no.acntech.easycontainers.AbstractContainer
import no.acntech.easycontainers.ContainerBuilder
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.k8s.K8sConstants.CORE_API_GROUP
import no.acntech.easycontainers.output.LineReader
import no.acntech.easycontainers.util.text.NEW_LINE
import no.acntech.easycontainers.util.text.SPACE
import org.awaitility.Awaitility.await
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


internal class K8sContainer(
    builder: ContainerBuilder,
) : AbstractContainer(builder) {

    private inner class PodWatcher {
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        fun start() {
            executor.execute {
                while (continueLoop()) {
                    try {
                        refreshPodsAndCheckStatus()
                        TimeUnit.MILLISECONDS.sleep(3 * 1000)
                    } catch (e: InterruptedException) {
                        log.info("Pod watcher interrupted!", e)
                        Thread.currentThread().interrupt() // Restore interrupted status
                    } catch (e: Exception) {
                        log.error("Error while watching pods: ${e.message}", e)
                    }
                }
            }
        }

        fun shutdown() {
            executor.shutdownNow()
            log.info("Pod watcher shutdown")
        }

        private fun continueLoop(): Boolean = !Thread.currentThread().isInterrupted

    }

    private inner class ContainerLogger {
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        fun start() {
            val podLogWatch = client.pods()
                .inNamespace(getNamespace())
                .withName(pods.first().first.metadata.name)
                .watchLog()

            val reader = LineReader(podLogWatch.output, builder.lineCallback)

            executor.execute {
                reader.read()
            }
        }

        fun shutdown() {
            executor.shutdownNow()
            log.info("Container logger shutdown")
        }

    }

    companion object {
        const val CONTAINER_NAME_SUFFIX = "-container"
        const val POD_NAME_SUFFIX = "-pod"
        const val DEPLOYMENT_NAME_SUFFIX = "-deployment"
        const val SERVICE_NAME_SUFFIX = "-service"
    }

    var deployment: Deployment? = null
        private set

    var service: Service? = null
        private set

    val pods: List<Pair<Pod, List<Container>>>
        get() = podsBackingField.toList()

    val configMaps: List<ConfigMap>
        get() = configMapsBackingField.toList()

    private var client: KubernetesClient = KubernetesClientBuilder().build()

    private val podsBackingField: MutableList<Pair<Pod, List<Container>>> = mutableListOf()

    private val configMapsBackingField: MutableList<ConfigMap> = mutableListOf()

    private val podWatcher = PodWatcher()

    private val containerLogger = ContainerLogger()

    init {
        createDeployment()
        createService(isNodePort = K8sUtils.isRunningOutsideCluster())
    }

    @Synchronized
    override fun start() {
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

        pods?.let { pod ->
            pod.forEach { (pod, containers) ->
                log.info("Pod: ${pod.metadata.name}")
                containers.forEach { container ->
                    log.info("Container: ${container.name}")
                }
            }
        }

        if (pods.isEmpty()) {
            log.error("No pods found for service: ${service!!.metadata.name}")
            changeState(no.acntech.easycontainers.Container.State.FAILED)
            throw ContainerException("No pods found for service: ${service!!.metadata.name}")
        }

        containerLogger.start()

        val serviceName = service!!.metadata.name
        val namespace = service!!.metadata.namespace
        internalHost = "$serviceName.$namespace.svc.cluster.local"
        log.info("Container (service) host: $internalHost")

        changeState(no.acntech.easycontainers.Container.State.STARTED)
        podWatcher.start()
    }

    @Synchronized
    override fun stop() {
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
    }

    @Synchronized
    override fun remove() {
        deleteServiceIfExists()
        deleteDeploymentIfExists()

        val configMapsExists = client.configMaps()
            .inNamespace(getNamespace())
            .list()
            .items
            .any { item -> configMapsBackingField.any { backItem -> backItem.metadata.name == item.metadata.name } }

        if (configMapsExists) {
            configMapsBackingField.forEach { configMap ->
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

        podWatcher.shutdown()
        containerLogger.shutdown()

        changeState(no.acntech.easycontainers.Container.State.REMOVED)
    }

    private fun refreshPodsAndCheckStatus() {
        val refreshedPods = mutableListOf<Pair<Pod, List<Container>>>()

        podsBackingField.forEach { (pod, containers) ->
            val refreshedPod = client.pods()
                .inNamespace(pod.metadata.namespace)
                .withName(pod.metadata.name)
                .get()

            if (refreshedPod != null) {
                log.debug("Pod refreshed: ${refreshedPod.metadata.name} - current phase: ${refreshedPod.status?.phase}")

                val newState = when (refreshedPod.status?.phase) {
                    "Running" -> no.acntech.easycontainers.Container.State.RUNNING
                    "Pending" -> no.acntech.easycontainers.Container.State.STARTED
                    "Failed" -> no.acntech.easycontainers.Container.State.FAILED
                    "Succeeded" -> no.acntech.easycontainers.Container.State.STOPPED
                    "Unknown", null -> no.acntech.easycontainers.Container.State.UNKNOWN
                    else -> no.acntech.easycontainers.Container.State.UNKNOWN
                }

                changeState(newState)

                refreshedPods.add(Pair(refreshedPod, containers))
            }
        }

        // Remove the existing pairs from podsBackingField
        podsBackingField.removeIf { (pod, _) ->
            refreshedPods.any { (refreshedPod, _) -> refreshedPod.metadata.name == pod.metadata.name }
        }

        // Add the refreshed pairs to podsBackingField
        podsBackingField.addAll(refreshedPods)
    }

    private fun extractPodsAndContainers() {

        // Get namespace and label selector from the service
        val namespace = service!!.metadata.namespace
        val selector = service!!.spec.selector

        // List all pods in the namespace that match the service's selector
        val pods = client.pods().inNamespace(namespace).withLabels(selector).list().items

        // Pair each pod with its list of containers
        pods.forEach { pod ->
            podsBackingField.add(Pair(pod, pod.spec.containers))
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
            .withGroup(CORE_API_GROUP) // Core API group
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

        builder.configFiles.forEach { (name, configFile) ->

            // Extract directory and filename from mountPath, ensuring forward slashes
            val mountPath = File(configFile.mountPath).parent?.replace("\\", "/") ?: "/"
            val fileName = File(configFile.mountPath).name

            // Create a ConfigMap object with a single entry
            val configMap = ConfigMapBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(getNamespace())
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
                configMapsBackingField.add(it)
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

    private fun createPodSpec(container: Container, volumes: List<Volume>): PodSpec {
        return PodSpec().apply {
            containers = listOf(container)
            this.volumes = volumes
        }
    }

    private fun createDeploymentFromPodSpec(podSpec: PodSpec) {
        val labels = mapOf(K8sConstants.APP_LABEL to getName())

        val templateMetadata = ObjectMeta().apply {
            this.labels = labels
            name = this@K8sContainer.getName() + POD_NAME_SUFFIX
        }

        val podTemplateSpec = PodTemplateSpec().apply {
            metadata = templateMetadata
            spec = podSpec
        }

        val deploymentSpec = DeploymentSpec().apply {
            replicas = 1
            template = podTemplateSpec
            selector = LabelSelector(null, labels)
        }


        val deploymentMetadata = ObjectMeta().apply {
            name = this@K8sContainer.getName() + DEPLOYMENT_NAME_SUFFIX
            namespace = this@K8sContainer.getNamespace()
        }

        deployment = Deployment().apply {
            apiVersion = "apps/v1"
            kind = "Deployment"
            metadata = deploymentMetadata
            spec = deploymentSpec
        }.also {
            log.debug("Created deployment: $it")
            val yaml = Serialization.asYaml(it)
            log.info("Deployment YAML:$NEW_LINE$yaml")
        }
    }

    private fun createService(isNodePort: Boolean = false) {
        // Manually creating the service metadata
        val serviceMetadata = ObjectMeta()
        serviceMetadata.name = getName() + SERVICE_NAME_SUFFIX
        serviceMetadata.namespace = getNamespace()

        // Manually creating the service ports
        val servicePorts = getExposedPorts().map { internalPort ->
            ServicePortBuilder()
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
                }
                .build()
        }

        // Manually creating the service spec
        val serviceSpec = ServiceSpec().apply {
            this.selector = mapOf(K8sConstants.APP_LABEL to getName())
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
            log.info("Created service: $it")
            val yaml = Serialization.asYaml(it)
            log.info("Service YAML:$NEW_LINE$yaml")
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

}