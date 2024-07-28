package no.acntech.easycontainers.custom

import no.acntech.easycontainers.Environment.defaultRegistryCompleteURL
import no.acntech.easycontainers.Environment.k8sKanikoDataPvcName
import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.custom.KanikoContainer.KanikoContainerBuilder.Companion.KANIKO_DATA_VOLUME_MOUNT_PATH
import no.acntech.easycontainers.docker.DockerConstants.PROP_ENABLE_NATIVE_DOCKER_ENTRYPOINT_STRATEGY
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.text.EMPTY_STRING
import no.acntech.easycontainers.util.text.NEW_LINE
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import java.io.File


class KanikoContainer(
   builder: KanikoContainerBuilder,
) : GenericContainer(builder) {

   class KanikoContainerBuilder : GenericContainerBuilder() {

      internal var verbosity: Verbosity = Verbosity.INFO

      internal var imageName = ImageName.DEFAULT

      internal var registry: RegistryURL = RegistryURL.of(defaultRegistryCompleteURL.substringAfter("://"))

      internal var isInsecureRegistry: Boolean = !defaultRegistryCompleteURL.startsWith("https")

      internal var repository: RepositoryName = RepositoryName.DEFAULT

      internal val tags: MutableList<ImageTag> = mutableListOf()

      internal var dockerContextSubDir: String = EMPTY_STRING

      internal var deleteDockerContext: Boolean = false

      companion object {
         const val DEFAULT_KANIKO_IMAGE_URL = "gcr.io/kaniko-project/executor:latest"
         const val KANIKO_DATA_VOLUME_MOUNT_PATH = "/mnt/kaniko-data"

         private const val DOCKERFILE_ARG = "--dockerfile="
         private const val CONTEXT_ARG = "--context="
         private const val VERBOSITY_ARG = "--verbosity="
         private const val DESTINATION_ARG = "--destination="
      }

      init {
         executionMode = ExecutionMode.TASK
         containerPlatformType = ContainerPlatformType.KUBERNETES
         image = ImageURL.of(DEFAULT_KANIKO_IMAGE_URL)
         withCustomProperty(PROP_ENABLE_NATIVE_DOCKER_ENTRYPOINT_STRATEGY, "true")
      }

      override fun self(): KanikoContainerBuilder {
         return this
      }

      override fun build(): Container {
         checkBuildAllowed()

         withExecutionMode(ExecutionMode.TASK)

         if (tags.isEmpty()) {
            tags.add(ImageTag.LATEST)
         }

         if (volumes.isEmpty()) {
            withVolume(VolumeName.of(k8sKanikoDataPvcName), UnixDir.of(KANIKO_DATA_VOLUME_MOUNT_PATH))
         }

         prepareArgs()

         log.debug("Creating KanikoContainer using builder:$NEW_LINE$this")

         return KanikoContainer(this)
      }

      fun withImageName(imageName: String): KanikoContainerBuilder {
         return withImageName(ImageName.of(imageName))
      }

      fun withImageName(imageName: ImageName): KanikoContainerBuilder {
         this.imageName = imageName
         return this
      }

      fun withVerbosity(verbosity: String): KanikoContainerBuilder {
         return withVerbosity(Verbosity.valueOf(verbosity))
      }

      fun withVerbosity(verbosity: Verbosity): KanikoContainerBuilder {
         this.verbosity = verbosity
         return this
      }

      fun withRegistry(registry: RegistryURL): KanikoContainerBuilder {
         this.registry = registry
         return this
      }

      fun withRegistry(registry: String): KanikoContainerBuilder {
         return withRegistry(RegistryURL.of(registry))
      }

      fun withRepository(repository: String): KanikoContainerBuilder {
         return withRepository(RepositoryName.of(repository))
      }

      fun withRepository(repository: RepositoryName): KanikoContainerBuilder {
         this.repository = repository
         return this
      }

      fun withInsecureRegistry(insecure: Boolean): KanikoContainerBuilder {
         this.isInsecureRegistry = insecure
         return this
      }

      fun withTag(tag: String): KanikoContainerBuilder {
         return withTag(ImageTag.of(tag))
      }

      fun withTag(tag: ImageTag): KanikoContainerBuilder {
         tags.add(tag)
         return this
      }

      fun withTags(tags: Set<String>): KanikoContainerBuilder {
         tags.forEach { tag -> withTag(tag) }
         return this
      }

      fun withDeleteDockerContext(delete: Boolean): KanikoContainerBuilder {
         this.deleteDockerContext = delete
         return this
      }

      /**
       * Add a volume for the Dockerfile and Docker context. For Docker this is a named volume, for Kubernetes this is a
       * PersistentVolumeClaim name (in the test setup this name is
       * 'kaniko-data-pvc'). If the name doest not end with '-pvc', both the base name and the '-pvc' suffix will be attempted
       * used, i.e. Easycontainers will check the existence of both 'your-volume-name' and 'your-volume-name-pvc', in that order.
       * <p>
       * Note that a ContainerException will be thrown (for kubernetes) if a PersistentVolumeClaim (as explained above) does not
       * exist in the namespace.
       */
      fun withDockerContextVolume(name: VolumeName): KanikoContainerBuilder {
         withVolume(name, UnixDir.of(KANIKO_DATA_VOLUME_MOUNT_PATH))
         return this
      }

      fun withDockerContextVolume(name: String): KanikoContainerBuilder {
         return withDockerContextVolume(VolumeName.of(name))
      }

      fun withDockerContextVolume(volume: Volume): KanikoContainerBuilder {
         require(volume.mountDir.unwrap().startsWith(KANIKO_DATA_VOLUME_MOUNT_PATH)) {
            "The mount path for the Docker context volume must start with '$KANIKO_DATA_VOLUME_MOUNT_PATH'"
         }
         volumes.add(volume)
         return this
      }

      /**
       * If set, the subDir will be appended to the mount path for the Docker context volume.
       */
      fun withDockerContextSubDir(subDir: UnixDir): KanikoContainerBuilder {
         this.dockerContextSubDir = subDir.unwrap()
         return this
      }

      fun withDockerContextSubDir(subDir: String): KanikoContainerBuilder {
         return withDockerContextSubDir(UnixDir.of(subDir))
      }

      override fun toString(): String {
         return ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .appendSuper(super.toString())
            .append("imageName", imageName)
            .append("registry", registry)
            .append("repository", repository)
            .append("tags", tags)
            .append("isInsecureRegistry", isInsecureRegistry)
            .append("dockerContextSubDir", dockerContextSubDir)
            .append("deleteDockerContext", deleteDockerContext)
            .append("verbosity", verbosity)
            .toString()
      }

      private fun prepareArgs() {
         val dockerContextDir = KANIKO_DATA_VOLUME_MOUNT_PATH + dockerContextSubDir

         val args = mutableListOf(
            DOCKERFILE_ARG + "$dockerContextDir/Dockerfile",
            CONTEXT_ARG + "dir://" + dockerContextDir,
            VERBOSITY_ARG + verbosity
         )

         if (isInsecureRegistry) {
            args.add("--insecure")
         }

         tags.forEach { tag ->
            args.add(DESTINATION_ARG + "${registry}/$repository/$imageName:$tag")
         }

         withArgs(args.toList())

         log.debug("KanikoContainer args: $args")
      }

   }

   companion object {

      fun builder(): KanikoContainerBuilder {
         return KanikoContainerBuilder()
      }

   }

   override fun onDelete() {
      if ((builder as KanikoContainerBuilder).deleteDockerContext) {
         log.debug("Deleting Docker context")
         val dockerContextDir = KANIKO_DATA_VOLUME_MOUNT_PATH + builder.dockerContextSubDir
         FileUtils.deleteDirectory(File(dockerContextDir))
      }
   }

   init {
      log.debug("KanikoContainer created with builder:$NEW_LINE$builder")
   }

}