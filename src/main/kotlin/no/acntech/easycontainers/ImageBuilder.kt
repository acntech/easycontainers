package no.acntech.easycontainers

import no.acntech.easycontainers.docker.DockerImageBuilder
import no.acntech.easycontainers.kubernetes.K8sImageBuilder
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.output.OutputLineCallback
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

/**
 * An abstract class for building Docker images. The class is designed to be extended by specific implementations.
 *
 * <h2>Kubernetes quirks</h2>
 * In order to build Docker images in a k8s cluster (using Kaniko) from a test environment running outside the cluster, you would
 * normally use a shared volume between the host and the k8s cluster.
 * <p>
 * In order to share a folder between the host and the k8s cluster the following config apply for Docker Desktop on
 * WSL (Windows Subsystem for Linux):
 * <ul>
 *    <li> On the host the shared directory <i>must</i> be under /mnt/wsl/.. </li>
 *    <li> In the k8s cluster the shared directory <i>must</i> be specified to be under /run/desktop/mnt/host/wsl/.. </li>
 * <code><pre>
 *    apiVersion: v1
 * kind: PersistentVolume
 * metadata:
 *   name: kaniko-data-pv
 *   labels:
 *     type: local
 * spec:
 *   storageClassName: hostpath
 *   capacity:
 *     storage: 100Mi
 *   accessModes:
 *     - ReadWriteMany
 *   hostPath: /run/desktop/mnt/host/wsl/kaniko-data
 * </pre></code>
 * <p>
 * When creating and configuring the ImageBuilder, this path must be used as the local path.
 * <pre><code>
 *    val imageBuilder = ImageBuilder.of(ContainerPlatformType.KUBERNETES)
 *          .withCustomProperty(ImageBuilder.PROP_LOCAL_KANIKO_DATA_PATH, "/mnt/wsl/kaniko-data")
 *          // other properties
 * </code></pre>
 * <p>
 * For KinD k8s the shared folder can be anywhere on the host file system, but the kind cluster must be configured
 * using a custom config applied at cluster startup in order for containers to mount the shared folder.
 * <p>
 * Example:
 * <p>
 * Host (wsl or native linux) path: /home/user/k8s-share/kaniko-data
 * <p>
 * Kind cluster config:
 * <code><pre>
 *    kind: Cluster
 * apiVersion: kind.x-k8s.io/v1alpha4
 * networking:
 *   apiServerAddress: "0.0.0.0"
 * nodes:
 * - role: control-plane
 *   extraMounts:
 *     - hostPath: /home/[user]/kaniko-data
 *       containerPath: /kaniko-data
 * </pre></code>
 * <p>
 * When creating and configuring the ImageBuilder (for kubernetes), this path must be used as the local path.
 * <pre><code>
 *    val imageBuilder = ImageBuilder.of(ContainerPlatformType.KUBERNETES)
 *          .withCustomProperty(ImageBuilder.PROP_LOCAL_KANIKO_DATA_PATH, "/home/[user]/kaniko-data")
 *          // other properties
 * </code></pre>
 */
abstract class ImageBuilder {

   enum class State {
      INITIALIZED,
      IN_PROGRESS,
      UNKNOWN,
      COMPLETED,
      FAILED
   }

   companion object {
      const val PROP_LOCAL_KANIKO_DATA_PATH = "kaniko-data.local.path"
      const val PROP_KANIKO_K8S_PVC_NAME = "kaniko-data.k8s.pvc.name"
      const val PROP_KANIKO_K8S_PV_NAME = "kaniko-data.k8s.pv.name"

      /**
       * Creates and returns of ImageBuilder based on the provided container type.
       *
       * @param type the type of container, defaults to ContainerType.KUBERNETES
       * @return an instance of ImageBuilder
       */
      fun of(type: ContainerPlatformType = ContainerPlatformType.KUBERNETES): ImageBuilder {
         return when (type) {
            ContainerPlatformType.DOCKER -> DockerImageBuilder()
            ContainerPlatformType.KUBERNETES -> K8sImageBuilder()
         }
      }

   }

   protected val log: Logger = LoggerFactory.getLogger(javaClass)

   private var state: State = State.INITIALIZED

   protected var registry: RegistryURL = RegistryURL.LOCAL

   protected var repository: RepositoryName = RepositoryName.DEFAULT

   protected var isInsecureRegistry: Boolean = false

   protected var dockerContextDir: Path = Path.of(System.getProperty("user.dir"))

   protected var name: ImageName = ImageName.DEFAULT

   protected val tags: MutableList<ImageTag> = mutableListOf()

   protected val labels: MutableMap<LabelKey, LabelValue> = mutableMapOf()

   protected var namespace: Namespace = Namespace.DEFAULT

   protected var verbosity: Verbosity = Verbosity.INFO

   protected var customProperties: MutableMap<String, String> = mutableMapOf()

   protected var outputLineCallback: OutputLineCallback = OutputLineCallback { _ -> }

   /**
    * Set the Docker context directory. This directory will be used as the build context when building the Docker image.
    *
    * @param dir the directory to use as the build context, if this exists the Dockerfile and all files in this directory
    * are used to build the image.
    * @exception IllegalArgumentException if the dir argument is not a directory
    */
   fun withDockerContextDir(dir: Path): ImageBuilder {
      require(Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) { "Docker context [$dir] is not a directory" }
      this.dockerContextDir = dir
      return this
   }

   /**
    * Sets the image registry for the ImageBuilder.
    *
    * @param registry the registry URL to be set
    * @return the current ImageBuilder instance
    */
   fun withImageRegistry(registry: RegistryURL): ImageBuilder {
      this.registry = registry
      return this
   }

   /**
    * Sets the insecure registry flag for the image builder.
    *
    * @param insecureRegistry true to allow insecure registry, false otherwise
    * @return the updated image builder instance
    */
   fun withInsecureRegistry(insecureRegistry: Boolean): ImageBuilder {
      this.isInsecureRegistry = insecureRegistry
      return this
   }

   /**
    * Sets the (registry) repository for the ImageBuilder.
    *
    * @param repository The repository to set for the ImageBuilder.
    * @return The updated ImageBuilder.
    */
   fun withRepository(repository: RepositoryName): ImageBuilder {
      this.repository = repository
      return this
   }

   /**
    * Sets the name of the image.
    *
    * @param name The name to set for the image.
    * @return The instance of the ImageBuilder with the name set.
    */
   fun withName(name: ImageName): ImageBuilder {
      this.name = name
      return this
   }

   /**
    * Adds the given image tag to the builder.
    *
    * @param tag the image tag to add
    * @return the updated ImageBuilder instance
    */
   fun withTag(tag: ImageTag): ImageBuilder {
      tags.add(tag)
      return this
   }

   /**
    * Adds a label to the ImageBuilder.
    *
    * @param key the label key
    * @param value the label value
    * @return the modified ImageBuilder instance with the added label
    */
   fun withLabel(key: LabelKey, value: LabelValue): ImageBuilder {
      labels[key] = value
      return this
   }

   /**
    * Sets the namespace for the ImageBuilder - this is only relevant for Kubernetes.
    *
    * @param namespace the namespace to set for the ImageBuilder
    * @return the updated ImageBuilder with the specified namespace set
    */
   fun withNamespace(namespace: Namespace): ImageBuilder {
      this.namespace = namespace
      return this
   }

   /**
    * Sets the verbosity level for the ImageBuilder.
    *
    * @param verbosity the verbosity level to set
    * @return the ImageBuilder instance
    */
   fun withVerbosity(verbosity: Verbosity): ImageBuilder {
      this.verbosity = verbosity
      return this
   }

   /**
    * Sets the provided OutputLineCallback for this ImageBuilder instance.
    *
    * @param outputLineCallback the LineCallback to be used
    * @return the modified ImageBuilder instance
    */
   fun withOutputLineCallback(outputLineCallback: OutputLineCallback): ImageBuilder {
      this.outputLineCallback = outputLineCallback
      return this
   }

   /**
    * Adds a custom property to the image builder.
    *
    * @param key   the key of the custom property
    * @param value the value of the custom property
    * @return the modified ImageBuilder object
    */
   fun withCustomProperty(key: String, value: String): ImageBuilder {
      customProperties[key] = value
      return this
   }

   /**
    * Retrieves the start time of the image build process.
    *
    * @return The start time of the image build as an Instant object, or null if the start time is unknown.
    */
   abstract fun getStartTime(): Instant?

   /**
    * Retrieves the finish time of the image building process.
    *
    * @return The finish time of the image building process as an Instant, or null if the process has not finished yet.
    */
   abstract fun getFinishTime(): Instant?


   /**
    * Builds an image.
    *
    * @return `true` if the image is successfully built, `false` otherwise.
    * @throws ContainerException if there is any exception encountered during the image build process.
    */
   @Throws(ContainerException::class)
   abstract fun buildImage(): Boolean

   /**
    * Returns a string representation of this ImageBuilder.
    * @return a string representation of this ImageBuilder
    */
   override fun toString(): String {
      return ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
         .append(this::name.name, name)
         .append(this::namespace.name, namespace)
         .append("startTime", getStartTime())
         .append("finishTime", getFinishTime())
         .append(this::registry.name, registry)
         .append(this::repository.name, repository)
         .append(this::isInsecureRegistry.name, isInsecureRegistry)
         .append(this::dockerContextDir.name, dockerContextDir)
         .append(this::tags.name, tags)
         .append(this::verbosity.name, verbosity)
         .append(this::customProperties.name, customProperties)
         .toString()
   }

   /**
    * Retrieves the current state.
    *
    * This method returns the current state of the object.
    *
    * @return the current state
    */
   @Synchronized
   fun getState(): State {
      return state
   }

   /**
    * Changes the state to the given new state.
    *
    * @param newState the new state to change to
    */
   @Synchronized
   protected fun changeState(newState: State) {
      if (newState == state || state == State.COMPLETED || state == State.FAILED) {
         return
      }

      val oldState = state
      state = newState
      log.info("Changed state from [$oldState] to [$state]")
   }

   /**
    * Checks if the current state is one of the specified states. If the current state is not one of the specified states,
    * an IllegalStateException is thrown with a descriptive error message.
    *
    * @param states the states to check against
    * @throws IllegalStateException if the current state is not one of the specified states
    */
   protected fun requireState(vararg states: State) {
      if (!states.contains(getState())) {
         throw IllegalStateException("In state [${getState()}], but required state is one of [${states.joinToString()}]")
      }
   }


}