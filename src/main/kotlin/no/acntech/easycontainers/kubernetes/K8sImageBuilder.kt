package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import io.fabric8.kubernetes.client.utils.Serialization
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.ImageBuilder
import no.acntech.easycontainers.kubernetes.K8sConstants.REGISTRY_DEFAULT_PORT
import no.acntech.easycontainers.model.ImageTag
import no.acntech.easycontainers.util.platform.PlatformUtils
import no.acntech.easycontainers.util.text.COLON
import no.acntech.easycontainers.util.text.FORWARD_SLASH
import no.acntech.easycontainers.util.text.NEW_LINE
import org.apache.commons.io.FileUtils
import org.awaitility.Awaitility.await
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists


/**
 * K8s builder for building a Docker image using a Kaniko job - see https://github.com/GoogleContainerTools/kaniko
 */
internal class K8sImageBuilder(
   private val client: KubernetesClient = K8sClientFactory.createDefaultClient(),
) : ImageBuilder() {

   companion object {

      private const val JOB_TTL_SECONDS_AFTER_FINISHED = 300 // 5 minutes

      private const val DEFAULT_KANIKO_IMAGE = "gcr.io/kaniko-project/executor:latest"
      private const val KANIKO_CONTAINER_NAME = "kaniko-container"
      private const val KANIKO_DATA_VOLUME_NAME = "kaniko-data-pv"
      private const val KANIKO_DOCKER_CONFIG_VOLUME_NAME = "kaniko-docker-config-volume"
      private const val KANIKO_DATA_VOLUME_CLAIM_NAME = "kaniko-data-pvc"
      private const val KANIKO_DATA_VOLUME_K8S_MOUNT_PATH = "/mnt/kaniko-data"
      private const val KANIKO_DOCKER_PATH = "/kaniko/.docker"
      private const val KANIKO_CONFIG_FILE = "config.json"

      private const val DOCKERFILE_ARG = "--dockerfile="
      private const val CONTEXT_ARG = "--context="
      private const val VERBOSITY_ARG = "--verbosity="
      private const val DESTINATION_ARG = "--destination="
   }

   private val uuid: String = UUID.randomUUID().toString()

   private val jobName = "kaniko-job-$uuid"

   private var job: Job? = null

   private var deleteContextDir = false

   private val startTime: AtomicReference<Instant> = AtomicReference<Instant>(null)

   private val finishTime: AtomicReference<Instant> = AtomicReference<Instant>(null)

   private var kanikoConfigMap: ConfigMap? = null // May be created during Job creating for insecure registries

   private val AccessChecker = AccessChecker(client)

   /**
    * Build the image using a Kaniko jobb. This method will block the calling thread until the job is completed.
    */
   override fun buildImage(): Boolean {
      checkPreconditionsAndInitialize()

      val contextDir = createActualDockerContextDir().also { dir ->
         log.info("Using '$dir' as the actual Docker context dir")
      }

      Thread.sleep(1000) // Sleep for 1 second to allow the context dir to be created and visible to the pod

      createAndDeployKanikoJob(contextDir)

      changeState(State.IN_PROGRESS)

      val podName = getPodNameForJob().also {
         log.info("Pod name for Kaniko job '$jobName': $it")
         if (it == null) {
            log.error("Unable to retrieve pod name for job '$jobName'")
            changeState(State.FAILED)
            return false
         }
      }

      // INVARIANT: A pod exists for the job

      // Log/debug the pod events
      client.pods().inNamespace(namespace.unwrap()).withName(podName).watch(LoggingWatcher())

      // Stream the logs from the pod
      val containerLogStreamer = ContainerLogStreamer(
         podName = podName!!,
         namespace = namespace.unwrap(),
         outputLineCallback = outputLineCallback
      )
      // Start the log streamer in a separate (virtual) thread
      Thread.startVirtualThread {
         containerLogStreamer.run()
      }

      // Wait for the job to complete
      try {
         waitBlockingToCompletion()
      } finally {
         cleanUp(containerLogStreamer, contextDir)
      }

      return getState() == State.COMPLETED
   }

   private fun cleanUp(containerLogStreamer: ContainerLogStreamer, contextDir: String) {
      // Stop the log streamer
      containerLogStreamer.stop()

      // Delete the context dir if the delete flag is set
      if (deleteContextDir) {
         log.info("Deleting context dir '$contextDir'")
         FileUtils.deleteDirectory(File(contextDir))
      }

      // Delete the config map if it was created
      kanikoConfigMap?.let {
         log.info("Deleting the Kaniko config map '${it.metadata.name}'")
         client.configMaps().inNamespace(namespace.unwrap()).resource(kanikoConfigMap).delete()
      }
   }

   override fun getStartTime(): Instant? {
      return startTime.get()
   }

   override fun getFinishTime(): Instant? {
      return finishTime.get()
   }

   private fun checkPreconditionsAndInitialize() {
      require(getState() == State.INITIALIZED) { "Image build already started: ${getState()}" }

      changeState(State.IN_PROGRESS)

      if (tags.isEmpty()) {
         tags.add(ImageTag.LATEST)
      }

      log.info("Using namespace '$namespace'")
      log.info("Using image name '$name'")
      log.info("Using Docker context dir: $dockerContextDir")
      log.info("Using registry '$registry'")
      log.info("Using repository '$repository'")
      log.info("Using tags: $tags")

   }

   private fun createActualDockerContextDir(): String {
      return if (K8sUtils.isRunningOutsideCluster()) {
         handleRunningOutsideCluster()
      } else {
         handleRunningInsideCluster()
      }
   }

   private fun handleRunningOutsideCluster(): String {
      log.debug("Running OUTSIDE a k8s cluster")
      return if (dockerContextDir.exists()) { // The directory exists on the local filesystem
         processOutsideCluster()
      } else {
         // The directory doesn't exist on the local filesystem, assume it's a sub-directory on the (shared)
         // Kaniko data volume and that the Dockerfile and the context is already present
         dockerContextDirBelowMountPath().also {
            log.info("Using '$it' as the Docker context dir (not present on local filesystem)")
         }
      }
   }

   private fun processOutsideCluster(): String {
      var localKanikoPath = customProperties[PROP_LOCAL_KANIKO_DATA_PATH].also {
         log.debug("localKanikoPath: $it")
      }

      return if (localKanikoPath != null) {
         log.debug("Using local Kaniko data path: $localKanikoPath")

         if (PlatformUtils.isWindows() && PlatformUtils.getDefaultWslDistro() != null) {
            if (localKanikoPath.startsWith(FORWARD_SLASH)) { // Unix-dir, i.e. inside WSL
               // Return the directory as the $WSL share
               localKanikoPath = PlatformUtils.createDirectoryInWsl(localKanikoPath)
            } else if (!localKanikoPath.startsWith("\\\\wsl\$")) {
               // Otherwise it needs to be in the \\wsl$ share
               throw ContainerException("Invalid local Kaniko path: $localKanikoPath")
            }

         } else if (PlatformUtils.isLinux() || PlatformUtils.isMac()) {
            localKanikoPath = File(localKanikoPath).also {
               if (!(it.exists() || it.mkdirs())) {
                  log.warn("Unable to create or non-existing local Kaniko-data directory: $it")
               }
            }.absolutePath
         }

         if (dockerContextDir.startsWith(localKanikoPath)) {
            log.trace("Docker context dir '$dockerContextDir' is already under the Kaniko data path")
            requireDockerfile()
            KANIKO_DATA_VOLUME_K8S_MOUNT_PATH + dockerContextDir.absolutePathString().substring(localKanikoPath!!.length).also {
               log.info("Using '$it' as the Docker context dir (present on local WSL filesystem)")
            }

         } else {
            log.trace("Docker context dir '$dockerContextDir' is not under the local Kaniko data volume, preparing to copy it")
            copyDockerContextDirToUniqueSubDir(localKanikoPath!!)
            "$KANIKO_DATA_VOLUME_K8S_MOUNT_PATH/${uuid}".also {
               log.info("Using '$it' as the Docker context dir")
            }
         }

      } else {
         // Running on Linux or Mac - or Windows without WSL2 - we just have to assume that the Dockerfile and the context is
         // already present in the shared Kaniko data volume
         dockerContextDirBelowMountPath()
      }
   }

   private fun handleRunningInsideCluster(): String {
      log.debug("Running INSIDE a k8s cluster")
      if (Files.exists(dockerContextDir)) {
         throw IllegalStateException("Docker context dir '$dockerContextDir' is not a directory on the local filesystem")
      }

      return if (dockerContextDir.toString().startsWith(KANIKO_DATA_VOLUME_K8S_MOUNT_PATH)) {
         log.trace("Docker context dir '$dockerContextDir' is already under the mounted Kaniko data volume")
         requireDockerfile()
         dockerContextDir.toString().also { resultContextDir ->
            log.info("Using '$resultContextDir' as the Docker context dir")
         }
      } else { // Otherwise copy it to a unique directory under the Kaniko data volume
         log.trace("Docker context dir '$dockerContextDir' is not under the mounted Kaniko data volume, preparing to copy it")
         copyDockerContextDirToUniqueSubDir(KANIKO_DATA_VOLUME_K8S_MOUNT_PATH)
      }
   }

   private fun requireDockerfile() {
      if (!Files.exists(dockerContextDir.resolve("Dockerfile"))) {
         throw ContainerException("File 'Dockerfile' expected in '$dockerContextDir'")
      }
   }

   private fun dockerContextDirBelowMountPath(): String =
      Paths.get(KANIKO_DATA_VOLUME_K8S_MOUNT_PATH, dockerContextDir.toString().trim('/'))
         .toString().also { resultContextDir ->
            log.info("Using '$resultContextDir' as the Docker context dir (not present on local filesystem)")
         }

   private fun copyDockerContextDirToUniqueSubDir(baseDir: String): String {
      val uniqueContextDir = Paths.get(baseDir, uuid)

      try {
         Files.createDirectories(uniqueContextDir).also {
            log.debug("Created directory '$uniqueContextDir'")
         }
      } catch (e: IOException) {
         throw ContainerException("Unable to create directory '$uniqueContextDir'")
      }

      log.info("Copying Docker context dir '$dockerContextDir' to '$uniqueContextDir'")

      try {
         FileUtils.copyDirectory(dockerContextDir.toFile(), uniqueContextDir.toFile()).also {
            log.debug("Dockerfile and context successfully copied to '$uniqueContextDir'")
         }
      } catch (e: IOException) {
         throw ContainerException("Error copying Docker context dir '$dockerContextDir' to '$uniqueContextDir': ${e.message}", e)
      }

      return uniqueContextDir.toString()
   }

   private fun createAndDeployKanikoJob(contextDir: String) {
      job = createKanikoJob(contextDir)
      job = client.batch().v1()
         .jobs()
         .inNamespace(namespace.unwrap())
         .resource(job)
         .create().also {
            log.info("Kaniko job '$it' deployed in namespace '$namespace'")
         }
   }

   private fun createKanikoJob(dockerContextPath: String): Job {
      val args = prepareArguments(dockerContextPath)

      val volumeMounts: MutableList<VolumeMount> = mutableListOf(
         createVolumeMount(
            KANIKO_DATA_VOLUME_NAME,
            KANIKO_DATA_VOLUME_K8S_MOUNT_PATH
         )
      )

      val configVolumeMount = if (isInsecureRegistry) createInsecureRegistryConfigVolumeMount() else null

      configVolumeMount?.let {
         volumeMounts.add(it)
      }

      // Create the container
      val container: Container = createContainer(args, volumeMounts)

      // Create the volumes
      val volumes: MutableList<Volume> = mutableListOf(createDataVolume())

      // Add the config map volume if it was created
      configVolumeMount?.let {
         volumes.add(createConfigMapVolume())
      }

      // Now we have the container and the volume(s) - create the job
      return createJob(container, volumes).also {
         log.debug("Created Kaniko job: {}", it.metadata.name)
         log.info("${NEW_LINE}Kaniko job YAML:$NEW_LINE${Serialization.asYaml(it)}")
      }

   }

   private fun prepareArguments(dockerContextPath: String): MutableList<String> {
      val args = mutableListOf(
         DOCKERFILE_ARG + "$dockerContextPath/Dockerfile",
         CONTEXT_ARG + "dir://" + dockerContextPath,
         VERBOSITY_ARG + verbosity
      )

      if (isInsecureRegistry) {
         args.add("--insecure")
      }

      tags.forEach { tag ->
         args.add(DESTINATION_ARG + "${registry}/$repository/$name:$tag")
      }

      return args.also {
         log.debug("Kaniko build arguments: {}", it)
      }
   }

   private fun createInsecureRegistryConfigVolumeMount(): VolumeMount {
      val registryVal = registry.unwrap()
      val host = if (registryVal.contains(COLON)) registryVal.substringBefore(COLON) else registryVal
      val port = if (registryVal.contains(COLON)) registryVal.substringAfter(COLON).toInt() else REGISTRY_DEFAULT_PORT
      createConfigMap(listOf(Pair(host, port)))
      return createVolumeMount(KANIKO_DOCKER_CONFIG_VOLUME_NAME, KANIKO_DOCKER_PATH)
   }

   private fun createVolumeMount(name: String, mountPath: String): VolumeMount {
      return VolumeMountBuilder()
         .withName(name)
         .withMountPath(mountPath)
         .build().also {
            log.debug("Created volume mount: {}", it)
         }
   }

   private fun createContainer(args: MutableList<String>, volumeMounts: MutableList<VolumeMount>): Container {
      return ContainerBuilder()
         .withName(KANIKO_CONTAINER_NAME)
         .withImage(DEFAULT_KANIKO_IMAGE)
         .withArgs(args)
         .withVolumeMounts(volumeMounts)
         .apply {
            if (volumeMounts.size > 1) {
               withEnv(
                  EnvVarBuilder()
                     .withName("DOCKER_CONFIG")
                     .withValue(KANIKO_DOCKER_PATH)
                     .build()
               )
            }
         }
         .build()
   }

   private fun createDataVolume(): Volume {
      return VolumeBuilder()
         .withName(KANIKO_DATA_VOLUME_NAME)
         .withPersistentVolumeClaim(
            PersistentVolumeClaimVolumeSourceBuilder()
               .withClaimName(KANIKO_DATA_VOLUME_CLAIM_NAME)
               .build()
         )
         .build().also {
            log.debug("Created data volume: {}", it)
         }
   }

   private fun createConfigMapVolume(): Volume {
      return VolumeBuilder()
         .withName(KANIKO_DOCKER_CONFIG_VOLUME_NAME)
         .withNewConfigMap()
         .withName(kanikoConfigMap!!.metadata.name)
         .endConfigMap()
         .build().also {
            log.debug("Created config map volume: {}", it)
         }
   }

   private fun createJob(container: Container, volumes: List<Volume>): Job {
      return JobBuilder()
         .withApiVersion("batch/v1")
         .withNewMetadata()
         .withName(jobName)
         .endMetadata()
         .withNewSpec()
         .withTtlSecondsAfterFinished(JOB_TTL_SECONDS_AFTER_FINISHED)
         .withTemplate(
            PodTemplateSpecBuilder()
               .withNewMetadata()
               .endMetadata()
               .withNewSpec()
               .withContainers(container)
               .withVolumes(volumes)
               .withRestartPolicy("Never")
               .endSpec()
               .build()
         )
         .endSpec()
         .build()
   }

   private fun createConfigMap(registries: List<Pair<String, Int>>) {
      // Convert the list of pairs to a list of "host:port" strings
      val registriesList = registries.map { "${it.first}:${it.second}" }

      // Generate the config.json contents
      val config = """
          {
              "insecure-registries" : ${registriesList.joinToString(", ", "[", "]") { "\"$it\"" }}
          }
          """.trimIndent()

      // Create the ConfigMap
      kanikoConfigMap = ConfigMapBuilder()
         .withNewMetadata()
         .withName("kaniko-docker-config-${uuid}")
         .endMetadata()
         .addToData(KANIKO_CONFIG_FILE, config)
         .build()

      log.debug("Created Kaniko config map: {}", kanikoConfigMap)
      log.debug("ConfigMap YAML:$NEW_LINE${Serialization.asYaml(kanikoConfigMap)}")

      kanikoConfigMap = client.configMaps().inNamespace(namespace.unwrap()).resource(kanikoConfigMap).create()
   }

   private fun getPodNameForJob(maxWaitTimeSeconds: Long = 100L): String? {
      var podName: String? = null

      try {
         await().atMost(maxWaitTimeSeconds, TimeUnit.SECONDS).until {
            val pods = client.pods().inNamespace(namespace.unwrap()).withLabel("job-name", jobName).list().items.also {
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

      // Lambda for changing the state and counting down the latch
      val jobWatcher = object : Watcher<Job> {

         override fun eventReceived(action: Watcher.Action, job: Job) {
            log.debug("Received event '${action.name}' on job with status '${job.status}'")
            if (job.status != null && job.status.startTime != null) {
               startTime.set(Instant.parse(job.status.startTime))
            }
            if (job.status != null && job.status.conditions != null) {
               for (condition in job.status.conditions) {
                  handleJobCondition(condition, job, latch)
               }
            }
         }

         override fun onClose(cause: WatcherException?) {
            log.info("Watcher closed")
            cause?.let { nonNullCause ->
               log.error("Due to error: ${nonNullCause.message}", nonNullCause)
               changeState(State.FAILED)
            }
            latch.countDown()
         }
      }

      try {
         client.batch().v1().jobs().inNamespace(namespace.unwrap()).withName(jobName).watch(jobWatcher)
         latch.await(timeoutValue, timeoutUnit)
      } catch (e: Exception) {
         log.error("Error watching job: ${e.message}", e)
         changeState(State.UNKNOWN)
         throw ContainerException("Error watching job: ${e.message}", e)
      }
   }

   private fun handleJobCondition(condition: JobCondition, job: Job, latch: CountDownLatch) {
      when (condition.type) {
         "Complete" -> handleJobCompletion(condition, job, latch)
         "Failed" -> handleJobFailure(condition, job, latch)
      }
   }

   private fun handleJobCompletion(condition: JobCondition, job: Job, latch: CountDownLatch) {
      if ("True" == condition.status) {
         val completionDateTimeVal = job.status.completionTime
         if (completionDateTimeVal != null) {
            finishTime.set(Instant.parse(completionDateTimeVal))
            val duration = java.time.Duration.between(startTime.get(), finishTime.get())
            val minutes = duration.toMinutes()
            // subtract whole minutes out of original duration in seconds to get remaining seconds
            val remainingSeconds = duration.toSeconds() - TimeUnit.MINUTES.toSeconds(minutes)
            val jobCompletionLog = "Job '$jobName' completed with completion time: $completionDateTimeVal"
            log.info(jobCompletionLog)
            log.info("Job '$jobName' took approximately: $minutes minutes and $remainingSeconds seconds")
         }
         changeState(State.COMPLETED)
         latch.countDown()
         log.trace("Latch decremented, job '$jobName' completed")
      }
   }

   private fun handleJobFailure(condition: JobCondition, job: Job, latch: CountDownLatch) {
      if ("True" == condition.status) {
         log.error("Job '$jobName' failed with reason: ${condition.reason}")
         changeState(State.FAILED)
         latch.countDown()
         log.trace("Latch decremented, job '$jobName' failed")
      }
   }

}