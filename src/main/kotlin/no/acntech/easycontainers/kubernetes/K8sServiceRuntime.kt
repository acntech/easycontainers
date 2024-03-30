package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.utils.Serialization
import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.kubernetes.ErrorSupport.handleK8sException
import no.acntech.easycontainers.model.ContainerState
import no.acntech.easycontainers.model.Host
import no.acntech.easycontainers.util.text.NEW_LINE
import org.awaitility.Awaitility
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Represents a Kubernetes service runtime that deploys and manages a container and associated resources.
 *
 * @property container The container to be deployed.
 * @property client The Kubernetes client used to interact with the cluster.
 */
class K8sServiceRuntime(
   container: GenericContainer,
   client: KubernetesClient = K8sClientFactory.createDefaultClient(),
) : K8sRuntime(container, client) {

   private var deployment = createDeployment()

   private var service: Service? =
      if (container.getExposedPorts().isNotEmpty()) {
         createService(isNodePort = K8sUtils.isRunningOutsideCluster())
      } else {
         null
      }

   override fun start() {
      container.changeState(ContainerState.INITIALIZING, ContainerState.UNINITIATED)

      log.info("Starting container: ${container.getName()}")
      log.debug("Using container config$NEW_LINE${container.builder}")

      try {
         createNamespaceIfAllowedAndNotExists()
         deleteServiceIfExists()
         deleteDeploymentIfExists()

         deployment = client.apps().deployments()
            .inNamespace(container.getNamespace().value)
            .resource(deployment)
            .create().also {
               log.info("Created k8s deployment: $it")
            }

         service?.let {
            service = client.services().inNamespace(container.getNamespace().value).resource(service).create().also {
               log.info("Deployed k8s service: $it")
            }
         }

         extractPodsAndContainers()

         // INVARIANT: podsBackingField/pods is not empty

         watchPods()

         containerLogStreamer = ContainerLogStreamer(
            this.pods.first().first.metadata.name,
            container.getNamespace().value,
            client,
            container.builder.outputLineCallback
         )

         GENERAL_EXECUTOR_SERVICE.execute(containerLogStreamer!!)

      } catch (e: Exception) {
         handleK8sException(e, log)
      }

      service?.let {
         val serviceName = service!!.metadata.name
         val namespace = service!!.metadata.namespace
         host = Host.of("$serviceName.$namespace.svc.cluster.local").also {
            log.info("Container (service) host: $it")
         }
      }

      super.start()

      // Note: We're not changing state here since this is handled by the PodWatcher started in watchPods()
   }

   override fun stop() {
      log.debug("Stopping container: ${container.getName()}")

      container.requireOneOfStates(ContainerState.RUNNING, ContainerState.FAILED)

      val exists = client.apps()
         .deployments()
         .inNamespace(container.getNamespace().value)
         .withName(getDeploymentName())
         .get() != null

      if (exists) {
         log.debug("Scaling deployment to 0: ${container.getName()}")
         client.apps()
            .deployments()
            .inNamespace(container.getNamespace().value)
            .withName(getDeploymentName())
            .scale(0)

         // Await all pods to be removed
         Awaitility.await()
            .atMost(120, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.SECONDS)
            .until {
               client.pods()
                  .inNamespace(container.getNamespace().value)
                  .withLabels(selectorLabels)
                  .list().items.isEmpty()
            }

         finishedAt.compareAndSet(null, Instant.now())

      } else {
         log.warn("Deployment to stop (scale to 0) not found: ${getDeploymentName()}")
      }

      containerLogStreamer?.stop()
      super.stop()
      container.changeState(ContainerState.STOPPED)
   }

   override fun kill() {
      log.debug("Killing container: ${container.getName()}")

      container.requireOneOfStates(ContainerState.RUNNING, ContainerState.FAILED)
      deleteDeploymentIfExists(true)
      finishedAt.compareAndSet(null, Instant.now())
      containerLogStreamer?.stop()
      container.changeState(ContainerState.STOPPED)
   }

   override fun delete(force: Boolean) {
      log.debug("Deleting container (force=$force): ${container.getName()}")

      if (!force) {
         container.requireOneOfStates(ContainerState.STOPPED, ContainerState.FAILED)
      }

      deleteDeploymentIfExists(true) // Forceful deletion ensures that the deployment is deleted even if it's not stopped
      finishedAt.compareAndSet(null, Instant.now())
      deleteServiceIfExists()
      super.delete(force)
      container.changeState(ContainerState.DELETED)
   }

   private fun createDeployment(): Deployment {
      val k8sContainer = createContainer()
      val (volumes, volumeMounts) = createVolumes()
      k8sContainer.volumeMounts = volumeMounts

      val containerPort = container.getExposedPorts().firstOrNull()?.value

      containerPort?.let { port ->
         log.info("Exposed port '$port' found for container '${container.getName()}' - creating probes for liveness and readiness")
      }

      val (livenessProbe, readinessProbe) = createProbes(containerPort)
      k8sContainer.livenessProbe = livenessProbe
      k8sContainer.readinessProbe = readinessProbe

      val podSpec = createPodSpec(k8sContainer, volumes)

      // Create the deployment
      return createDeploymentFromPodSpec(podSpec)
   }

   private fun createDeploymentFromPodSpec(podSpec: PodSpec): Deployment {
      val podTemplateMetadata = ObjectMeta().apply {
         this.labels = selectorLabels + createDefaultLabels()
         name = container.getName().value + POD_NAME_SUFFIX
      }

      val podTemplateSpec = PodTemplateSpec().apply {
         metadata = podTemplateMetadata
         spec = podSpec
      }

      val deploymentSpec = DeploymentSpec().apply {
         replicas = 1
         template = podTemplateSpec
         selector = LabelSelector(null, selectorLabels)
      }

      val deploymentMetadata = ObjectMeta().apply {
         name = getDeploymentName()
         namespace = container.getNamespace().value
         val stringLabels: Map<String, String> = container.getLabels().flatMap { (key, value) ->
            listOf(key.value to value.value)
         }.toMap()

         labels = stringLabels + createDefaultLabels()
      }

      return Deployment().apply {
         apiVersion = "apps/v1"
         kind = "Deployment"
         metadata = deploymentMetadata
         spec = deploymentSpec
      }.also {
         log.debug("Created deployment: $it")
         log.debug("Deployment YAML:$NEW_LINE${Serialization.asYaml(it)}")
      }
   }

   private fun getDeploymentName(): String {
      return container.getName().value + DEPLOYMENT_NAME_SUFFIX
   }

   private fun createService(isNodePort: Boolean = false): Service {
      // Manually creating the service metadata
      val serviceMetadata = ObjectMeta()
      serviceMetadata.name = container.getName().value + SERVICE_NAME_SUFFIX
      serviceMetadata.namespace = container.getNamespace().value
      serviceMetadata.labels = createDefaultLabels()

      // Manually creating the service ports
      val servicePorts = container.builder.exposedPorts.map { (name, internalPort) ->
         ServicePortBuilder()
            .withName(name.value)
            .withPort(internalPort.value)
            .withNewTargetPort(internalPort.value)
            .apply {
               if (isNodePort && container.hasPortMapping(internalPort)) {
                  val nodePort = container.getMappedPort(internalPort).value
                  if (nodePort !in K8sConstants.NODE_PORT_RANGE_START..K8sConstants.NODE_PORT_RANGE_END) {
                     log.warn(
                        "The mapped NodePort $nodePort for internal port $internalPort " +
                           "is outside the recommended range: " +
                           "${K8sConstants.NODE_PORT_RANGE_START}-${K8sConstants.NODE_PORT_RANGE_END}"
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
      return Service().apply {
         apiVersion = "v1"
         kind = "Service"
         metadata = serviceMetadata
         spec = serviceSpec
      }.also {
         log.debug("Created service: $it")
         log.info("Service YAML:$NEW_LINE${Serialization.asYaml(it)}")
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

   private fun deleteDeploymentIfExists(force: Boolean = false) {
      val namespaceName = container.getNamespace().value
      val deploymentName = getDeploymentName()
      val deployment = client.apps().deployments().inNamespace(namespaceName).withName(deploymentName).get()

      deployment?.let {
         log.info("Deleting deployment: $deploymentName")
         client.apps().deployments()
            .inNamespace(namespaceName)
            .withName(deploymentName)
            .apply {
               if (force) {
                  this@K8sServiceRuntime.log.info("Delete : $deploymentName")
                  withPropagationPolicy(DeletionPropagation.FOREGROUND)
                  withGracePeriod(0L)
               }
            }
            .delete()

         if (force) { // Tear down all pods
            for (pod in client.pods().inNamespace(namespaceName).withLabels(selectorLabels).list().items) {
               client.pods().inNamespace(namespaceName).withName(pod.metadata.name).delete()
            }
         }

         // Wait for the deployment to be removed
         Awaitility.await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.SECONDS)
            .until {
               client.apps().deployments()
                  .inNamespace(namespaceName)
                  .withName(deploymentName)
                  .get() == null
            }

         log.info("Deployment deleted: $deploymentName")

      } ?: log.warn("Cannot delete non-existing deployment: $deploymentName")
   }

   private fun deleteServiceIfExists() {
      val namespaceName = container.getNamespace().value
      val serviceName = container.getName().value + SERVICE_NAME_SUFFIX

      client.services().inNamespace(namespaceName).withName(serviceName).get()?.let {
         log.info("Deleting service: $serviceName")

         client.services()
            .inNamespace(namespaceName)
            .withName(serviceName)
            .delete()

         // Wait for the service to be removed
         Awaitility.await()
            .atMost(30, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .until {
               client.services().inNamespace(namespaceName).withName(serviceName).get() == null
            }

         log.info("Service successfully deleted: $serviceName")

      } ?: log.warn("Service does not exist - nothing to delete: $serviceName")
   }

}
