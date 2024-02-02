package no.acntech.easycontainers

import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.output.LineCallback
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

abstract class ImageBuilder {

   enum class State {
      INITIALIZED,
      IN_PROGRESS,
      UNKNOWN,
      COMPLETED,
      FAILED
   }

   protected val log: Logger = LoggerFactory.getLogger(javaClass)

   private var state: State = State.INITIALIZED

   protected var registry: RegistryURL = RegistryURL.LOCAL

   protected var repository: RepositoryName = RepositoryName.DEFAULT

   protected var isInsecureRegistry: Boolean = false

   protected var dockerContextDir: Path = Path.of(System.getProperty("user.dir"))

   protected var name: ImageName = ImageName.DEFAULT

   protected val tags: MutableList<ImageTag> = mutableListOf()

   protected var namespace: Namespace = Namespace.DEFAULT

   protected var verbosity: Verbosity = Verbosity.INFO

   protected var lineCallback: LineCallback = LineCallback { _ -> }

   /**
    * Set the Docker context directory. This directory will be used as the build context when building the Docker image.
    *
    * @param dir the directory to use as the build context, if this exists the Dockerfile and all files in this directory
    * are used to build the image.
    */
   fun withDockerContextDir(dir: Path): ImageBuilder {
      require(Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) { "Docker context [$dir] is not a directory" }
      this.dockerContextDir = dir
      return this
   }

   /**
    * A protocol prefix is optional, if not provided "https://" is most likely used, unless the registry address is
    * an IP address, then "http://" is used.
    */
   fun withImageRegistry(registry: RegistryURL): ImageBuilder {
      this.registry = registry
      return this
   }

   fun withInsecureRegistry(insecureRegistry: Boolean): ImageBuilder {
      this.isInsecureRegistry = insecureRegistry
      return this
   }

   fun withRepository(repository: RepositoryName): ImageBuilder {
      this.repository = repository
      return this
   }

   fun withName(name: ImageName): ImageBuilder {
      this.name = name
      return this
   }

   fun withTag(tag: ImageTag): ImageBuilder {
      tags.add(tag)
      return this
   }

   fun withNamespace(namespace: Namespace): ImageBuilder {
      this.namespace = namespace
      return this
   }

   fun withVerbosity(verbosity: Verbosity): ImageBuilder {
      this.verbosity = verbosity
      return this
   }

   fun withLogLineCallback(lineCallback: LineCallback): ImageBuilder {
      this.lineCallback = lineCallback
      return this
   }

   abstract fun getStartTime(): Instant?

   abstract fun getFinishTime(): Instant?

   @Throws(ContainerException::class)
   abstract fun buildImage(): Boolean

   override fun toString(): String {
      return ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
         .append(this::name.name, name)
         .append(this::registry.name, registry)
         .append(this::repository.name, repository)
         .append(this::dockerContextDir.name, dockerContextDir)
         .append(this::tags.name, tags)
         .append(this::namespace.name, namespace)
         .append(this::verbosity.name, verbosity)
         .toString()
   }

   @Synchronized
   fun getState(): State {
      return state
   }

   @Synchronized
   protected fun changeState(newState: State) {
      if (newState == state || state == State.COMPLETED || state == State.FAILED) {
         return
      }

      val oldState = state
      state = newState
      log.info("Changed state from [$oldState] to [$state]")
   }

   protected fun requireState(vararg states: State) {
      if (!states.contains(getState())) {
         throw IllegalStateException("In state [${getState()}], but required state is one of [${states.joinToString()}]")
      }
   }


}