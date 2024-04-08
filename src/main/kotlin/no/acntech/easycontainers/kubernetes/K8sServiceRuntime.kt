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
import no.acntech.easycontainers.util.lang.prettyPrintMe
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

   init {
      service?.let { nonNullService ->
         val serviceName = nonNullService.metadata.name
         val namespace = nonNullService.metadata.namespace
         host = Host.of("$serviceName.$namespace.svc.cluster.local").also {
            log.info("Container (service) host: $it")
         }
      }
   }
   override fun stop() {
      log.debug("Stopping container: ${container.getName()}")

      container.requireOneOfStates(ContainerState.RUNNING, ContainerState.FAILED)

      val exists = client.apps()
         .deployments()
         .inNamespace(container.getNamespace().value)
         .withName(getResourceName())
         .get() != null

      if (exists) {
         log.debug("Scaling Deployment for pod/container to 0: ${container.getName()}")

         client.apps()
            .deployments()
            .inNamespace(container.getNamespace().value)
            .withName(getResourceName())
            .scale(0)

         // Await all pods to be removed
         Awaitility.await()
            .atMost(120, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.SECONDS)
            .until {
               client.pods()
                  .inNamespace(container.getNamespace().value)
                  .withName(pod.get().metadata.name)
                  .get() == null
            }

         finishedAt.compareAndSet(null, Instant.now())

      } else {
         log.warn("Deployment to stop (scale to 0) not found: ${getResourceName()}")
      }

      super.stop()
   }

   override fun kill() {
      log.debug("Killing container: ${container.getName()}")

      container.requireOneOfStates(ContainerState.RUNNING, ContainerState.FAILED)
      deleteDeploymentIfExists(true)
      finishedAt.compareAndSet(null, Instant.now())
      container.changeState(ContainerState.STOPPED)
   }

   override fun deploy() {
      log.debug("Creating Deployment '${getResourceName()}' in namespace '${getNamespace()}'")

      createNamespaceIfAllowedAndNotExists()
      deleteService()
      deleteDeploymentIfExists()

      deployment = client.apps()
         .deployments()
         .inNamespace(container.getNamespace().value)
         .resource(deployment)
         .create().also {
            log.info("Created k8s deployment: ${it.prettyPrintMe()}")
         }

      service?.let {
         service = client.services()
            .inNamespace(getNamespace())
            .resource(service)
            .create().also {
            log.info("Deployed k8s service: ${it.prettyPrintMe()}")
         }
      }
   }

   override fun getResourceName(): String {
      return container.getName().value + DEPLOYMENT_NAME_SUFFIX
   }

   override fun deleteResources() {
      deleteDeploymentIfExists(true) // Forceful deletion ensures that the deployment is deleted even if it's not stopped
      deleteService()
   }

   override fun configure(k8sContainer: Container) {
      val containerPort = container.getExposedPorts().firstOrNull()?.value
      containerPort?.let { port ->
         log.info("Exposed port '$port' found for container '${container.getName()}' - creating probes for liveness and readiness")
      }

      val (livenessProbe, readinessProbe) = createProbes(containerPort)
      k8sContainer.livenessProbe = livenessProbe
      k8sContainer.readinessProbe = readinessProbe
   }

   override fun configure(podSpec: PodSpec) {
      podSpec.restartPolicy = "Always"
   }

   private fun createDeployment(): Deployment {
      val podTemplateSpec = createPodTemplateSpec()

      val deploymentSpec = DeploymentSpec().apply {
         replicas = 1
         selector = LabelSelector(null, selectorLabels)
         template = podTemplateSpec
      }

      return Deployment().apply {
         apiVersion = "apps/v1"
         kind = "Deployment"
         metadata = getResourceMetaData()
         spec = deploymentSpec
      }.also {
         log.debug("Created Deployment: ${it.prettyPrintMe()}")
         log.debug("Deployment YAML:$NEW_LINE${Serialization.asYaml(it)}")
      }
   }

   private fun createService(isNodePort: Boolean = false): Service {
      // Manually creating the service metadata
      val serviceMetadata = ObjectMeta()
      serviceMetadata.name = container.getName().value + SERVICE_NAME_SUFFIX
      serviceMetadata.namespace = getNamespace()
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
         log.debug("Created service: ${it.prettyPrintMe()}")
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
         log.info("Readiness probe created$NEW_LINE${it.prettyPrintMe()}")
      }

      return Pair(livenessProbe, readinessProbe)
   }

   private fun deleteDeploymentIfExists(force: Boolean = false) {
      findDeployment(getNamespace(), getResourceName())?.let {
         log.info("Deleting deployment: ${getResourceName()}")
         deleteDeployment(force)
         // Tear down all pods if force deletion
         if (force) {
            deletePods()
         }

         // Wait for the deployment to be removed
         Awaitility.await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .until { findDeployment(getNamespace(), getResourceName()) == null }

         log.info("Deployment deleted: ${getResourceName()}")
      } ?: log.warn("Cannot delete non-existing deployment: ${getResourceName()}")
   }

   private fun findDeployment(namespaceName: String, deploymentName: String): Deployment? =
      client.apps().deployments().inNamespace(namespaceName).withName(deploymentName).get()

   private fun deleteDeployment(force: Boolean) {
      client.apps().deployments()
         .inNamespace(getNamespace())
         .withName(getResourceName())
         .apply {
            if (force) {
               this@K8sServiceRuntime.log.info("Force delete : $${getResourceName()}")
               withPropagationPolicy(DeletionPropagation.FOREGROUND)
               withGracePeriod(0L)
            }
         }
         .delete()
   }

   private fun deletePods() {
      for (pod in client.pods().inNamespace(getNamespace()).withLabels(podLabels).list().items) {
         client.pods().inNamespace(getNamespace()).withName(pod.metadata.name).delete()
      }
   }

   private fun deleteService() {
      val serviceName = container.getName().value + SERVICE_NAME_SUFFIX

      if (isServiceExisting(serviceName)) {
         log.info("Deleting service: $serviceName")
         getServiceClient(serviceName)
            .delete()
         // Wait for the service to be removed
         Awaitility.await()
            .atMost(30, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .until { !isServiceExisting(serviceName) }
         log.info("Service successfully deleted: $serviceName")

      } else log.warn("Service does not exist - nothing to delete: $serviceName")
   }

   private fun isServiceExisting(serviceName: String) = getServiceClient(serviceName).get() != null

   private fun getServiceClient(serviceName: String) = client.services().inNamespace(getNamespace()).withName(serviceName)

}
