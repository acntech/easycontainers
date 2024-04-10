package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition
import io.fabric8.kubernetes.api.model.batch.v1.JobSpec
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import io.fabric8.kubernetes.client.utils.Serialization
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.model.ContainerState
import no.acntech.easycontainers.model.Host
import no.acntech.easycontainers.util.lang.prettyPrintMe
import no.acntech.easycontainers.util.text.NEW_LINE
import org.apache.commons.lang3.time.DurationFormatUtils
import java.time.Instant
import java.util.concurrent.CountDownLatch

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

   private val jobName
      get() = job.metadata.name

   private val completionLatch = CountDownLatch(1)

   override fun start() {
      super.start()

      createWatcher()

      pod.get().let { k8sPod ->
         host = Host.of("${k8sPod.metadata.name}.$namespace.pod.cluster.local").also {
            log.debug("Host for pod: $it")
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
         .inNamespace(namespace)
         .withName(jobName)
         .get()

      if (existingJob != null) {
         deleteResources()
      } else {
         log.info("Job ${job.metadata.name} does not exist, no need to delete.")
      }

      super.stop()
   }

   override fun deploy() {
      log.debug("Deploying job '${job.metadata.name}' in namespace '$namespace'")

      job = client.batch().v1()
         .jobs()
         .inNamespace(namespace)
         .resource(job)
         .create().also {
            log.info("Job '${job.metadata.name}' deployed in namespace '$namespace'$NEW_LINE${it.prettyPrintMe()}")
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
            .inNamespace(namespace)
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

   private fun createWatcher() {

      // Lambda for changing the Container state
      val jobWatcher = object : Watcher<Job> {

         override fun eventReceived(action: Watcher.Action, job: Job) {
            log.debug("Received event '${action.name}' on job with status '${job.status}'")

            if (job.status != null && job.status.startTime != null) {
               startedAt.set(Instant.parse(job.status.startTime))
            }

            if (job.status != null && job.status.conditions != null) {
               for (condition in job.status.conditions) {
                  handleJobCondition(condition)
               }
            }
         }

         override fun onClose(cause: WatcherException?) {
            cause?.let { nonNullCause ->
               log.error("Job '$jobName' watcher closed due to error: ${nonNullCause.message}", nonNullCause)
               container.changeState(ContainerState.FAILED)
            } ?: run {
               log.info("Job '$jobName' watcher closed")
               container.changeState(ContainerState.STOPPED)
            }
         }

      }

      try {
         client.batch().v1()
            .jobs()
            .inNamespace(namespace)
            .withName(jobName)
            .watch(jobWatcher)

      } catch (e: Exception) {
         log.error("Error watching job: ${e.message}", e)
         container.changeState(ContainerState.FAILED)
         throw ContainerException("Error watching job: ${e.message}", e)
      }
   }

   private fun handleJobCondition(condition: JobCondition) {
      when (condition.type) {
         "Complete" -> handleJobCompletion(condition)
         "Failed" -> handleJobFailure(condition)
      }
   }

   private fun handleJobCompletion(condition: JobCondition) {
      if ("True" == condition.status) {
         val completionDateTimeVal = job.status.completionTime
         completionDateTimeVal?.let {
            finishedAt.set(Instant.parse(completionDateTimeVal))

            DurationFormatUtils.formatDurationWords(
               finishedAt.get().toEpochMilli() - startedAt.get().toEpochMilli(),
               true,
               true
            ).let { duration ->
               log.info("Job '$jobName' took approximately: $duration")
            }
         }
         container.changeState(ContainerState.STOPPED)
         completionLatch.countDown()
         log.trace("Latch decremented, job '$jobName' completed")
      }
   }

   private fun handleJobFailure(condition: JobCondition) {
      if ("True" == condition.status) {
         log.error("Job '$jobName' failed with reason: ${condition.reason}")
         container.changeState(ContainerState.FAILED)
         completionLatch.countDown()
         log.trace("Latch decremented, job '$jobName' failed")
      }
   }


}
