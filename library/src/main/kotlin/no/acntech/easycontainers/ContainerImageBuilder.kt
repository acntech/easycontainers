package no.acntech.easycontainers

import no.acntech.easycontainers.k8s.K8sConstants.DEFAULT_NAMESPACE
import no.acntech.easycontainers.output.LineCallback
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class ContainerImageBuilder {

   enum class State {
      NOT_STARTED,
      IN_PROGRESS,
      UNKNOWN,
      COMPLETED,
      FAILED
   }

   protected val log: Logger = LoggerFactory.getLogger(javaClass)

   private var state: State = State.NOT_STARTED

   protected var registry: String? = null

   protected lateinit var dockerContextDir: String

   protected var name: String? = null

   protected val tags: MutableList<String> = mutableListOf()

   protected var namespace: String = DEFAULT_NAMESPACE

   protected var verbosity: String = "info"

   protected var lineCallback: LineCallback = LineCallback { _ -> }

   /**
    * Set the Docker context directory. This directory will be used as the build context when building the Docker image.
    *
    * @param dir the directory to use as the build context, if this exists the Dockerfile and all files in this directory
    * are used to build the image.
    */
   fun withDockerContextDir(dir: String): ContainerImageBuilder {
      this.dockerContextDir = dir
      return this
   }

   fun withImageRegistry(registry: String): ContainerImageBuilder {
      this.registry = registry
      return this
   }

   fun withName(name: String): ContainerImageBuilder {
      this.name = name
      return this
   }

   fun withTag(tag: String): ContainerImageBuilder {
      tags.add(tag)
      return this
   }

   fun withNamespace(namespace: String): ContainerImageBuilder {
      this.namespace = namespace
      return this
   }

   fun withVerbosity(verbosity: String): ContainerImageBuilder {
      this.verbosity = verbosity
      return this
   }

   fun withLogLineCallback(lineCallback: LineCallback): ContainerImageBuilder {
      this.lineCallback = lineCallback
      return this
   }

   @Throws(ContainerException::class)
   abstract fun buildImage(): Boolean

   override fun toString(): String {
      return ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
         .append(this::name.name, name)
         .append(this::registry.name, registry)
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