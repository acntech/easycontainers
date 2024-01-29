package no.acntech.easycontainers.k8s

import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition
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
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference


/**
 * K8s builder for building a Docker image using a Kaniko job - see https://github.com/GoogleContainerTools/kaniko
 */
internal class K8sContainerImageBuilder(
   private val client: KubernetesClient = KubernetesClientBuilder().build(),
) : ContainerImageBuilder() {

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
      private const val TAG_LATEST = "latest"

      private const val KANIKO_DATA_LOCAL_PATH = "/home/thomas/kind/data/kaniko-data"
      private const val KANIKO_DATA_LOCAL_PATH_WSL_DOCKER_DESKTOP = "mnt/wsl/share/kaniko-data"


      private const val DOCKERFILE_ARG = "--dockerfile="
      private const val CONTEXT_ARG = "--context="
      private const val VERBOSITY_ARG = "--verbosity="
      private const val DESTINATION_ARG = "--destination="

      private const val HTTP_PROTOCOL_PREFIX = "http://"
      private const val HTTPS_PROTOCOL_PREFIX = "https://"

      private var kanikoDataLocalPath: String? = null

      init {
         if (K8sUtils.isRunningOutsideCluster()) {
            if (OperatingSystemUtils.isWindows() && OperatingSystemUtils.getDefaultWSLDistro() != null) {
               // Won't fail if the directory already exists

               //            kanikoDataLocalPath = OperatingSystemUtils.createDirectoryInWSL("mnt/wsl/share/kaniko-data") // For Docker Desktop

               // For WSL2 with e.g. Kind or Minikube
               kanikoDataLocalPath = OperatingSystemUtils.createDirectoryInWSL(KANIKO_DATA_LOCAL_PATH)

            } else if (OperatingSystemUtils.isLinux()) {
               kanikoDataLocalPath = File(System.getProperty("user.home") + "/kaniko-data").also {
                  if (!it.exists()) {
                     if (!it.mkdirs()) {
                        LoggerFactory.getLogger(K8sContainerImageBuilder::class.java).warn("Unable to create directory: $it")
                     }
                  }
               }.absolutePath
            }

            LoggerFactory.getLogger(K8sContainerImageBuilder::class.java).info("Kaniko data local path: $kanikoDataLocalPath")
         }
      }

   }

   private val uuid: String = UUID.randomUUID().toString()

   private val jobName = "kaniko-job-$uuid"

   private var job: Job? = null

   private var deployedJob: Job? = null

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

      val contextDir = createActualDockerContextDir().also {
         log.info("Using '$it' as the actual Docker context dir")
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
         cleanUp(containerLogStreamer, contextDir)
      }

      return getState() == State.COMPLETED
   }

   private fun cleanUp(containerLogStreamer: ContainerLogStreamer, contextDir: String) {
      // Stop the log streamer
      containerLogStreamer.stop()

      // Delete the context dir if it was created
      if (deleteContextDir) {
         log.info("Deleting context dir '$contextDir'")
         //            FileUtils.deleteDirectory(File(contextDir))
      }

      // Delete the config map if it was created
      kanikoConfigMap?.let {
         log.info("Deleting Kaniko config map '${kanikoConfigMap!!.metadata.name}'")
         client.configMaps().inNamespace(namespace).resource(kanikoConfigMap).delete()
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
         handleRunningOutsideCluster()
      } else {
         handleRunningInsideCluster()
      }
   }

   private fun handleRunningOutsideCluster(): String {
      log.debug("Running OUTSIDE a k8s cluster")
      return if (File(dockerContextDir).isDirectory) { // The directory exists on the local filesystem
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
      return if (kanikoDataLocalPath != null) { // Running on Windows with WSL
         val kanikoDataLocalPath = kanikoDataLocalPath!!
         if (dockerContextDir.startsWith(kanikoDataLocalPath)) {
            log.trace("Docker context dir '$dockerContextDir' is already under the Kaniko data volume")
            requireDockerfile()
            KANIKO_DATA_VOLUME_K8S_MOUNT_PATH + dockerContextDir.substring(kanikoDataLocalPath.length).also {
               log.info("Using '$it' as the Docker context dir (present on local WSL filesystem)")
            }

         } else {
            log.trace("Docker context dir '$dockerContextDir' is not under the local Kaniko data volume, preparing to copy it")
            copyDockerContextDirToUniqueSubDir(kanikoDataLocalPath)
            "$KANIKO_DATA_VOLUME_K8S_MOUNT_PATH/${uuid}".also {
               log.info("Using '$it' as the Docker context dir")
            }
         }

      } else { // Running on Linux or Mac - or Windows without WSL2
         // We have to assume that the Dockerfile and the context is already present in the shared Kaniko data volume
         dockerContextDirBelowMountPath()
      }
   }

   private fun handleRunningInsideCluster(): String {
      log.debug("Running INSIDE a k8s cluster")
      if (!File(dockerContextDir).isDirectory) {
         throw IllegalStateException("Docker context dir '$dockerContextDir' is not a directory on the local filesystem")
      }

      return if (dockerContextDir.startsWith(KANIKO_DATA_VOLUME_K8S_MOUNT_PATH)) {
         log.trace("Docker context dir '$dockerContextDir' is already under the mounted Kaniko data volume")
         requireDockerfile()
         dockerContextDir.also { resultContextDir ->
            log.info("Using '$resultContextDir' as the Docker context dir")
         }

      } else { // Otherwise copy it to a unique directory under the Kaniko data volume
         log.trace("Docker context dir '$dockerContextDir' is not under the mounted Kaniko volume, preparing to copy it")
         copyDockerContextDirToUniqueSubDir(KANIKO_DATA_VOLUME_K8S_MOUNT_PATH)
      }
   }

   private fun requireDockerfile() {
      if (!File(dockerContextDir, "Dockerfile").exists()) {
         throw ContainerException("Dockerfile expected in '$dockerContextDir'")
      }
   }

   private fun dockerContextDirBelowMountPath(): String =
      "$KANIKO_DATA_VOLUME_K8S_MOUNT_PATH/${dockerContextDir.trim('/')}".also { resultContextDir ->
         log.info("Using '$resultContextDir' as the Docker context dir (not present on local filesystem)")
      }

   private fun copyDockerContextDirToUniqueSubDir(baseDir: String): String {
      val uniqueContextDir = "$baseDir/${uuid}"

      if (!File(uniqueContextDir).mkdirs()) {
         throw IllegalStateException("Unable to create directory '$uniqueContextDir'")
      }

      log.info("Copying Docker context dir '$dockerContextDir' to '$uniqueContextDir'")
      FileUtils.copyDirectory(File(dockerContextDir), File(uniqueContextDir))

      return uniqueContextDir
   }

   private fun createAndDeployKanikoJob(contextDir: String) {
      job = createKanikoJob(contextDir)
      deployedJob = client.batch().v1().jobs().inNamespace(namespace).resource(job).create().also {
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

      val configVolumeMount = createInsecureRegistryConfigVolumeMount()

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
      val job = createJob(container, volumes)

      log.debug("Created Kaniko job: {}", job.metadata.name)
      log.info("${NEW_LINE}Kaniko job YAML:$NEW_LINE${Serialization.asYaml(job)}")

      return job
   }

   private fun prepareArguments(dockerContextPath: String): MutableList<String> {
      val registry = registry!!

      val args = mutableListOf(
         DOCKERFILE_ARG + "$dockerContextPath/Dockerfile",
         CONTEXT_ARG + "dir://" + dockerContextPath,
         VERBOSITY_ARG + verbosity
      )

      if (registry.startsWith(HTTP_PROTOCOL_PREFIX)) {
         args.add("--insecure")
      }

      val actualRegistry = registry.removePrefix(HTTP_PROTOCOL_PREFIX).removePrefix(HTTPS_PROTOCOL_PREFIX).also {
         log.debug("Actual registry to push to: {}", it)
      }

      tags.forEach { tag ->
         args.add(DESTINATION_ARG + "${actualRegistry}/$name:$tag")
      }

      return args.also {
         log.debug("Kaniko build arguments: {}", it)
      }
   }

   private fun createInsecureRegistryConfigVolumeMount(): VolumeMount? {
      return if (registry!!.startsWith(HTTP_PROTOCOL_PREFIX)) {
         val actualRegistryWithoutProtocol = registry!!.removePrefix(HTTP_PROTOCOL_PREFIX)
         val host =
            if (actualRegistryWithoutProtocol.contains(":")) actualRegistryWithoutProtocol.substringBefore(":") else actualRegistryWithoutProtocol
         val port =
            if (actualRegistryWithoutProtocol.contains(":")) actualRegistryWithoutProtocol.substringAfter(":").toInt() else 5000
         createConfigMap(listOf(Pair(host, port)))
         createVolumeMount(KANIKO_DOCKER_CONFIG_VOLUME_NAME, KANIKO_DOCKER_PATH)
      } else null
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

      kanikoConfigMap = client.configMaps().inNamespace(namespace).resource(kanikoConfigMap).create()
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