package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobSpec
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.utils.Serialization
import net.bytebuddy.build.Plugin.Engine.ErrorHandler
import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.model.ContainerState
import no.acntech.easycontainers.model.Host
import no.acntech.easycontainers.util.lang.prettyPrintMe
import no.acntech.easycontainers.util.text.EMPTY_STRING
import no.acntech.easycontainers.util.text.NEW_LINE
import no.acntech.easycontainers.util.text.truncate
import java.util.*

/**
 * Represents a Kubernetes Job runtime for a given container.
 *
 * @property container The container to run as a Kubernetes Job.
 * @property client The Kubernetes client used to interact with the Kubernetes API. Default is a default client instance.
 */
class K8sJobRuntime(
   container: GenericContainer,
   client: KubernetesClient = K8sClientFactory.createDefaultClient(),
) : K8sRuntime(container, client) {

   private var job: Job = createJob()

   override fun start() {
      super.start()

      pod.get().let { k8sPod ->
         host = Host.of("${k8sPod.metadata.name}.${getNamespace()}.pod.cluster.local").also {
            log.info("Host for pod: $it")
         }
      }
   }

   /**
    * Stop the job by deleting the Kubernetes Job resource.
    */
   override fun stop() {
      container.changeState(ContainerState.TERMINATING, ContainerState.RUNNING)

      val existingJob = client.batch().v1()
         .jobs()
         .inNamespace(getNamespace())
         .withName(job.metadata.name)
         .get()

      if (existingJob != null) {
         deleteResources()
      } else {
         log.info("Job ${job.metadata.name} does not exist, no need to delete.")
      }

      super.stop()
   }

   override fun deploy() {
      log.debug("Deploying job '${job.metadata.name}' in namespace '${getNamespace()}'")

      job = client.batch().v1()
         .jobs()
         .inNamespace(getNamespace())
         .resource(job)
         .create().also {
            log.info("Job '${job.metadata.name}' deployed in namespace '${getNamespace()}'$NEW_LINE${it.prettyPrintMe()}")
         }
   }

   override fun getResourceName(): String {
      return container.getName().value + "-job"
   }

   override fun configure(k8sContainer: Container) {
      // No-op
   }
   override fun configure(podSpec: PodSpec) {
      podSpec.restartPolicy = "Never"
   }

   override fun deleteResources() {
      try {
         client.batch().v1()
            .jobs()
            .inNamespace(getNamespace())
            .withName(job.metadata.name)
            .delete()
      } catch (e: Exception) {
         log.warn("Failed to delete job '${job.metadata.name}': ${e.message}", e)
         ErrorSupport.handleK8sException(e, log)
      }
   }

   private fun createJob(): Job {
      val podTemplateSpec = createPodTemplateSpec()

      val jobSpec = JobSpec().apply {
         template = podTemplateSpec
         backoffLimit = 0
         completions = 1
      }

      return Job().apply {
         apiVersion = "batch/v1"
         kind = "Job"
         metadata = getResourceMetaData()
         spec = jobSpec
      }.also {
         log.debug("Created job:${it.prettyPrintMe()}")
         log.debug("Job YAML:$NEW_LINE${Serialization.asYaml(it)}")
      }
   }


}
