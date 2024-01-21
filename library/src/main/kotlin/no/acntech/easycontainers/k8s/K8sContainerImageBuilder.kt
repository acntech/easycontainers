package no.acntech.easycontainers.k8s

import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import io.fabric8.kubernetes.client.utils.Serialization
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.ContainerImageBuilder
import no.acntech.easycontainers.util.platform.OperatingSystemUtils
import no.acntech.easycontainers.util.text.NEW_LINE
import org.apache.commons.io.FileUtils
import org.awaitility.Awaitility.await
import java.io.File
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


/**
 * K8s builder for building a Docker image using a Kaniko job - see https://github.com/GoogleContainerTools/kaniko
 */
internal class K8sContainerImageBuilder(
   private val client: KubernetesClient = KubernetesClientBuilder().build(),
) : ContainerImageBuilder() {

   companion object {

      private const val DEFAULT_KANIKO_IMAGE = "gcr.io/kaniko-project/executor:latest"
      private const val KANIKO_CONTAINER_NAME = "kaniko-container"
      private const val KANIKO_DATA_VOLUME_NAME = "kaniko-data-pv"
      private const val KANIKO_DATA_VOLUME_CLAIM_NAME = "kaniko-data-pvc"
      private const val KANIKO_DATA_VOLUME_K8S_MOUNT_PATH = "/mnt/kaniko-data"
      private const val TAG_LATEST = "latest"

      private var kanikoDataLocalPath: String? = null

      init {
         if (OperatingSystemUtils.isWindows()) {
            // Won't fail if the directory already exists
            kanikoDataLocalPath = OperatingSystemUtils.createDirectoryInWSL("mnt/wsl/share/kaniko-data")
         }
      }
   }

   private val uuid: String = UUID.randomUUID().toString()

   private val jobName = "kaniko-job-$uuid"

   private var job: Job? = null

   private var deployedJob: Job? = null

   /**
    * Build the image using a Kaniko jobb. This method will block the calling thread until the job is completed.
    */
   override fun buildImage(): Boolean {
      checkPreconditionsAndInitialize()

      val contextDir = createActualDockerContextDir().also {
         log.info("Using '$it' as the acltual Docker context dir")
      }

      createAndDeployKanikoJob(contextDir)

      changeState(State.IN_PROGRESS)

      val podName = getPodNameForJob().also {
         log.info("Pod name for job '$jobName': $it")
         if (it == null) {
            log.error("Unable to retrieve pod name for job '$jobName'")
            changeState(State.FAILED)
            return false
         }
      }

      // INVARIANT: A pod exists for the job

      // Log/debug the pod events
      client.pods().inNamespace(namespace).withName(podName).watch(LoggingWatcher())

      // Stream the logs from the pod
      val containerLogStreamer = ContainerLogStreamer(
         podName = podName!!,
         namespace = namespace,
         lineCallback = lineCallback
      )
      Thread.startVirtualThread {
         containerLogStreamer.run()
      }

      // Wait for the job to complete
      try {
         waitBlockingToCompletion()
      } finally {
         containerLogStreamer.stop()
      }

      return when (getState()) {
         State.COMPLETED -> true
         else -> false
      }

   }

   private fun checkPreconditionsAndInitialize() {
      require(getState() == State.NOT_STARTED) { "Image build already started" }
      require(name != null) { "name must be set" }
      require(registry != null) { "registry must be set" }

      changeState(State.IN_PROGRESS)

      log.info("Building image '$name' in registry '$registry'")

      if (tags.isEmpty()) {
         tags.add(TAG_LATEST)
      }

      log.info("Using tags: $tags")
      log.info("Using namespace '$namespace'")
   }

   private fun createActualDockerContextDir(): String {
      return if (K8sUtils.isRunningOutsideCluster()) {
         log.debug("Running OUTSIDE a k8s cluster")
         handleDockerContextDir(kanikoDataLocalPath)
      } else {
         log.debug("Running INSIDE a k8s cluster")
         handleDockerContextDir(KANIKO_DATA_VOLUME_K8S_MOUNT_PATH)
      }
   }

   private fun dockerDirExists(dockerContextDir: String): Boolean {
      return File(dockerContextDir).isDirectory
   }

   private fun dockerDirUnderPath(dockerContextDir: String, path: String?): Boolean {
      return path != null && dockerContextDir.startsWith(path)
   }

   private fun copyContextDir(uniqueContextDir: String) {
      if (!File(uniqueContextDir).mkdirs()) {
         throw IllegalStateException("Unable to create directory '$uniqueContextDir'")
      }
      log.info("Copying Docker context dir '$dockerContextDir' to '$uniqueContextDir'")
      FileUtils.copyDirectory(File(dockerContextDir!!), File(uniqueContextDir))
   }

   private fun generateMountPathContextDir(dockerContextDir: String): String {
      return "$KANIKO_DATA_VOLUME_K8S_MOUNT_PATH/${dockerContextDir.trim('/')}"
   }

   private fun checkContextDir(contextDir: String) {
      require(File(contextDir, "Dockerfile").exists()) { "Dockerfile expected in '$contextDir'" }
      log.info("Using '$contextDir' as the Docker context dir")
   }

   private fun handleDockerContextDir(path: String?): String {
      if (dockerDirExists(dockerContextDir)) {
         if (dockerDirUnderPath(dockerContextDir, path)) {
            checkContextDir(dockerContextDir)
            return dockerContextDir
         }
         val uniqueContextDir = "$path/${uuid}"
         copyContextDir(uniqueContextDir)
         checkContextDir(uniqueContextDir)
         return uniqueContextDir
      }
      return generateMountPathContextDir(dockerContextDir)
   }

   private fun createAndDeployKanikoJob(contextDir: String) {
      job = createKanikoJob(contextDir)
      deployedJob = client.batch().v1().jobs().inNamespace(namespace).resource(job).create().also {
         log.info("Kaniko job '$it' deployed in namespace '$namespace'")
      }
   }

   private fun createKanikoJob(dockerContextPath: String): Job {
      val args = mutableListOf(
         "--dockerfile=$dockerContextPath/Dockerfile",
         "--context=dir://$dockerContextPath",
         "--verbosity=$verbosity"
      )

      // Add a destination for each tag
      tags.forEach { tag ->
         args.add("--destination=${registry}/$name:$tag")
      }

      log.info("Kaniko args: $args")

      val container: Container = ContainerBuilder()
         .withName(KANIKO_CONTAINER_NAME)
         .withImage(DEFAULT_KANIKO_IMAGE)
         .withArgs(args)
         .withVolumeMounts(
            VolumeMountBuilder()
               .withName(KANIKO_DATA_VOLUME_NAME)
               .withMountPath(KANIKO_DATA_VOLUME_K8S_MOUNT_PATH)
               .build()
         )
         .build()

      return JobBuilder()
         .withApiVersion("batch/v1")
         .withNewMetadata()
         .withName(jobName)
         .endMetadata()
         .withNewSpec()
         .withTtlSecondsAfterFinished(60)
         .withTemplate(
            PodTemplateSpecBuilder()
               .withNewMetadata()
               .endMetadata()
               .withNewSpec()

               .withContainers(container)
               .withVolumes(
                  VolumeBuilder()
                     .withName(KANIKO_DATA_VOLUME_NAME)
                     .withPersistentVolumeClaim(
                        PersistentVolumeClaimVolumeSourceBuilder()
                           .withClaimName(KANIKO_DATA_VOLUME_CLAIM_NAME)
                           .build()
                     )
                     .build()
               )
               .withRestartPolicy("Never")
               .endSpec()
               .build()
         )
         .endSpec()
         .build().also { job ->
            log.debug("Created Kaniko job: {}", job)
            log.info("Kaniko job YAML:$NEW_LINE${Serialization.asYaml(job)}")
         }
   }

   private fun getPodNameForJob(maxWaitTimeSeconds: Long = 100L): String? {
      var podName: String? = null

      try {
         await().atMost(maxWaitTimeSeconds, TimeUnit.SECONDS).until {
            val pods = client.pods().inNamespace(namespace).withLabel("job-name", jobName).list().items.also {
               log.debug("Found pods for job-name '$jobName': ${getPodsInfo(it)}")
            }
            val pod = pods.firstOrNull { it.metadata.labels["job-name"] == jobName }
            pod?.also {
               podName = it.metadata.name
            } != null
         }
      } catch (e: Exception) {
         log.error("Error retrieving pod name: ${e.message}", e)
      }

      return podName.also {
         if (it == null) {
            log.warn("Timed out after $maxWaitTimeSeconds seconds for pod to become available for job-name: $jobName")
         }
      }
   }

   private fun getPodsInfo(pods: List<Pod>): String {
      return pods.joinToString(", ") { pod ->
         "Pod(name=${pod.metadata.name}, status=${pod.status.phase}, labels=${pod.metadata.labels})"
      }
   }

   private fun waitBlockingToCompletion(timeoutValue: Long = 10, timeoutUnit: TimeUnit = TimeUnit.MINUTES) {
      val latch = CountDownLatch(1)

      val jobWatcher = object : Watcher<Job> {
         override fun eventReceived(action: Watcher.Action, job: Job) {
            log.debug("Received event '${action.name}' on job with status '${job.status}'")
            if (job.status != null && job.status.conditions != null) {
               for (condition in job.status.conditions) {
                  if ("Complete" == condition.type && "True" == condition.status) {
                     val completionDateTimeVal = job.status.completionTime
                     val startDateTimeVal = job.status.startTime

                     if (startDateTimeVal != null && completionDateTimeVal != null) {
                        val startDateTime = java.time.Instant.parse(startDateTimeVal)
                        val completionDateTime = java.time.Instant.parse(completionDateTimeVal)

                        val duration = java.time.Duration.between(startDateTime, completionDateTime)
                        val minutes = duration.toMinutes()
                        // subtract whole minutes out of original duration in seconds to get remaining seconds
                        val remainingSeconds = duration.toSeconds() - TimeUnit.MINUTES.toSeconds(minutes)

                        log.info("Job '$jobName' completed with completion time: $completionDateTimeVal")
                        log.info("Job '$jobName' took approximately: $minutes minutes and $remainingSeconds seconds")
                     }

                     changeState(State.COMPLETED)
                     latch.countDown()
                     log.trace("Latch decremented, job '$jobName' completed")
                  } else if ("Failed" == condition.type && "True" == condition.status) {
                     log.error("Job '$jobName' failed with reason: ${condition.reason}")
                     changeState(State.FAILED)
                     latch.countDown()
                     log.trace("Latch decremented, job '$jobName' completed")
                  }
               }
            }
         }

         override fun onClose(cause: WatcherException?) {
            log.info("Watcher closed")
            if (cause != null) {
               log.error("Due to error: ${cause.message}", cause)
               changeState(State.FAILED)
            }
            latch.countDown()
         }

      }

      try {
         client.batch().v1().jobs().inNamespace(namespace).withName(jobName).watch(jobWatcher)
         latch.await(timeoutValue, timeoutUnit)
      } catch (e: Exception) {
         log.error("Error watching job: ${e.message}", e)
         changeState(State.UNKNOWN)
         throw ContainerException("Error watching job: ${e.message}", e)
      }
   }

}