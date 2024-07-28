package no.acntech.easycontainers.kubernetes

import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.Environment.k8sKanikoDataHostDir
import no.acntech.easycontainers.ImageBuilder
import no.acntech.easycontainers.custom.KanikoContainer
import no.acntech.easycontainers.custom.KanikoContainer.KanikoContainerBuilder.Companion.KANIKO_DATA_VOLUME_MOUNT_PATH
import no.acntech.easycontainers.model.Container
import no.acntech.easycontainers.model.ContainerPlatformType
import no.acntech.easycontainers.util.platform.PlatformUtils
import no.acntech.easycontainers.util.text.FORWARD_SLASH
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists


/**
 * K8s builder for building a Docker image using a Kaniko job - see https://github.com/GoogleContainerTools/kaniko
 */
internal class K8sImageBuilder : ImageBuilder() {

   private val uuid: String = UUID.randomUUID().toString()

   private val startTime: AtomicReference<Instant> = AtomicReference<Instant>(null)

   private val finishTime: AtomicReference<Instant> = AtomicReference<Instant>(null)

   private var subDir: String? = null

   /**
    * Build the image using a Kaniko jobb. This method will block the calling thread until the job is completed.
    */
   override fun buildImage(): Boolean {
      checkPreconditionsAndInitialize()

      createActualDockerContextDir().also { dir ->
         log.info("Using '$dir' as the actual Docker context dir")
      }

      val builder = KanikoContainer.builder()
      builder.withImageName(imageName)
      builder.withNamespace(namespace)
      builder.withContainerPlatformType(ContainerPlatformType.KUBERNETES)
      builder.withNamespace(namespace)
      builder.withRegistry(registry)
      builder.withRepository(repository)
      builder.withInsecureRegistry(isInsecureRegistry)
      builder.tags.forEach(::withTag)
      builder.withVerbosity(verbosity)
      builder.withOutputLineCallback { line -> log.debug("Kaniko-container-output: $line") }
      subDir?.let { builder.withDockerContextSubDir(it) }

      val container = builder.build()

      container.getRuntime().start()

      changeState(State.IN_PROGRESS)

      container.waitForCompletion(30, TimeUnit.SECONDS)

      return when (container.getState()) {
         Container.State.STOPPED, Container.State.DELETED -> {
            changeState(State.COMPLETED)
            true
         }

         else -> {
            changeState(State.FAILED)
            false
         }
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
   }

   private fun createActualDockerContextDir(): String {
      return if (K8sUtils.isRunningOutsideCluster()) {
         handleRunningOutsideCluster()
      } else {
         processInsideCluster()
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
      val actualLocalKanikoPath = processLocalKanikoPath(k8sKanikoDataHostDir)
      return processDockerContextDir(actualLocalKanikoPath)
   }

   private fun processLocalKanikoPath(localKanikoPath: String): String {
      return when {
         PlatformUtils.isWindows() && PlatformUtils.getDefaultWslDistro() != null -> processWindowsLocalKanikoPath(localKanikoPath)
         PlatformUtils.isLinux() || PlatformUtils.isMac() -> processUnixLocalKanikoPath(localKanikoPath)
         else -> localKanikoPath
      }
   }

   private fun processWindowsLocalKanikoPath(localKanikoPath: String): String {
      return when {
         // Unix-dir, i.e. inside WSL
         localKanikoPath.startsWith(FORWARD_SLASH) -> PlatformUtils.createDirectoryInWsl(localKanikoPath)
         // Otherwise it needs to be in the \\wsl$ share
         !localKanikoPath.startsWith("\\\\wsl\$") -> throw ContainerException("Invalid local Kaniko path: $localKanikoPath")
         else -> localKanikoPath
      }
   }

   private fun processUnixLocalKanikoPath(localKanikoPath: String): String {
      return File(localKanikoPath).apply {
         if (!exists() && !mkdirs()) {
            log.warn("Unable to create or non-existing local Kaniko-data directory: $this")
         }
      }.absolutePath
   }

   private fun processDockerContextDir(localKanikoPath: String?): String {
      return when {
         dockerContextDir.startsWith(
            localKanikoPath ?: return dockerContextDirBelowMountPath()
         ) -> processDockerContextDirBelowKanikoDataDir(localKanikoPath)

         else -> processDockerContextDirNotBelowKanikoDataDir(localKanikoPath)
      }
   }

   private fun processDockerContextDirBelowKanikoDataDir(localKanikoPath: String): String {
      log.trace("Docker context dir '$dockerContextDir' is already under the Kaniko data path")
      requireDockerfile()
      return (KANIKO_DATA_VOLUME_MOUNT_PATH + dockerContextDir.absolutePathString()
         .substring(localKanikoPath.length)).also {
         log.info("Using '$it' as the Docker context dir (present on local WSL filesystem)")
      }
   }

   private fun processDockerContextDirNotBelowKanikoDataDir(localKanikoPath: String): String {
      log.trace("Docker context dir '$dockerContextDir' is not under the local Kaniko data volume, preparing to copy it")
      copyDockerContextDirToUniqueSubDir(localKanikoPath)
      subDir = "$FORWARD_SLASH$uuid"
      return "$KANIKO_DATA_VOLUME_MOUNT_PATH/${uuid}".also {
         log.info("Using '$it' as the Docker context dir")
      }
   }

   private fun processInsideCluster(): String {
      log.debug("Running INSIDE a k8s cluster")
      if (Files.exists(dockerContextDir)) {
         throw IllegalStateException("Docker context dir '$dockerContextDir' is not a directory on the local filesystem")
      }

      return if (dockerContextDir.toString().startsWith(KANIKO_DATA_VOLUME_MOUNT_PATH)) {
         log.trace("Docker context dir '$dockerContextDir' is already below the mounted Kaniko data volume")
         requireDockerfile()
         dockerContextDir.toString().also { resultContextDir ->
            log.info("Using '$resultContextDir' as the Docker context dir")
         }
      } else { // Otherwise copy it to a unique directory under the Kaniko data volume
         log.trace("Docker context dir '$dockerContextDir' is not below the mounted Kaniko data volume, preparing to copy it")
         subDir = "$FORWARD_SLASH$uuid"
         copyDockerContextDirToUniqueSubDir(KANIKO_DATA_VOLUME_MOUNT_PATH)
      }
   }

   private fun requireDockerfile() {
      if (!Files.exists(dockerContextDir.resolve("Dockerfile"))) {
         throw ContainerException("File 'Dockerfile' expected in '$dockerContextDir'")
      }
   }

   private fun dockerContextDirBelowMountPath(): String =
      Paths.get(KANIKO_DATA_VOLUME_MOUNT_PATH, dockerContextDir.toString().trim('/'))
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

}