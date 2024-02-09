package no.acntech.easycontainers

import no.acntech.easycontainers.model.*
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * AbstractContainer represents a generic container that implements the Container interface.
 *
 * @property builder The ContainerBuilder used to build the container.
 */
abstract class AbstractContainer(
   protected val builder: ContainerBuilder,
) : Container {

   protected val log: Logger = LoggerFactory.getLogger(javaClass)

   private var state: Container.State = Container.State.CREATED

   init {
      if (builder.isEphemeral) {
         Runtime.getRuntime().addShutdownHook(Thread {
            if (state == Container.State.REMOVED) {
               return@Thread
            }
            log.info("ShutdownHook: stopping and removing container [${getName()}]")
            stop()
            remove()
         })
      }
   }

   @Synchronized
   override fun getState(): Container.State {
      return state
   }

   override fun getName(): ContainerName {
      return builder.name
   }

   override fun getNamespace(): Namespace {
      return builder.namespace
   }

   override fun getLabels(): Map<LabelKey, LabelValue> {
      return builder.labels.toMap()
   }

   override fun getEnv(): Map<EnvVarKey, EnvVarValue> {
      return builder.env.toMap()
   }

   override fun getCommand(): Executable? {
      return builder.command
   }

   override fun getArgs(): Args? {
      return builder.args
   }

   override fun getImage(): ImageURL {
      return builder.image
   }

   override fun getExposedPorts(): List<NetworkPort> {
      return builder.exposedPorts.values.toList()
   }

   override fun getMappedPort(port: NetworkPort): NetworkPort {
      return builder.portMappings[port] ?: throw IllegalArgumentException("Port [$port] is not mapped")
   }

   override fun getPortMappings(): Map<NetworkPort, NetworkPort> {
      return builder.portMappings.toMap()
   }

   override fun isEphemeral(): Boolean {
      return builder.isEphemeral
   }

   override fun toString(): String {
      return ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
         .append("state", state)
         .append("name", getName())
         .append("namespace", getNamespace())
         .append("labels", getLabels())
         .append("env", getEnv())
         .append("command", getCommand())
         .append("args", getArgs())
         .append("image", getImage())
         .append("exposedPorts", getExposedPorts())
         .append("mappedPorts", getPortMappings())
         .append("isEphemeral", isEphemeral())
         .append("host", getHost())
         .append("ipAddress", getIpAddress())
         .toString()
   }

   @Synchronized
   protected fun changeState(newState: Container.State) {
      if (newState == state || state == Container.State.REMOVED) {
         return
      }

      val oldState = state
      state = newState
      log.info("Container [${getName()}] changed state from [$oldState] to [$state]")
   }

   protected fun requireState(vararg states: Container.State) {
      if (!states.contains(getState())) {
         throw IllegalStateException("Container [${getName()}] is in state [${getState()}], required state is one of [${states.joinToString()}]")
      }
   }

}