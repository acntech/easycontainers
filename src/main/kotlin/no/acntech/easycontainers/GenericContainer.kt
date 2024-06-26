package no.acntech.easycontainers

import no.acntech.easycontainers.docker.DockerRuntime
import no.acntech.easycontainers.kubernetes.K8sJobRuntime
import no.acntech.easycontainers.kubernetes.K8sServiceRuntime
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.output.OutputLineCallback
import no.acntech.easycontainers.util.text.NEW_LINE
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


/**
 * GenericContainer represents a generic container implementation that can be customized and built using the provided builder.
 * It implements the Container interface.
 *
 * @property builder The builder object used to construct the GenericContainer instance.
 */
open class GenericContainer(
   internal val builder: GenericContainerBuilder,
) : Container {

   open class GenericContainerBuilder : BaseContainerBuilder<GenericContainerBuilder>() {

      override fun self(): GenericContainerBuilder {
         return this
      }

      override fun build(): Container {
         checkBuildAllowed()
         return GenericContainer(this)
      }

   }

   companion object {

      @OptIn(ExperimentalStdlibApi::class)
      private val LEGAL_STATE_TRANSITIONS: Map<Container.State, Set<Container.State>> = mapOf(
         Container.State.UNINITIATED to setOf(Container.State.INITIALIZING),
         Container.State.INITIALIZING to setOf(Container.State.RUNNING, Container.State.FAILED),
         Container.State.RUNNING to setOf(Container.State.TERMINATING, Container.State.STOPPED, Container.State.FAILED),
         Container.State.TERMINATING to setOf(Container.State.STOPPED, Container.State.DELETED, Container.State.FAILED),
         Container.State.STOPPED to setOf(Container.State.DELETED, Container.State.FAILED),
         Container.State.DELETED to emptySet(),
         Container.State.FAILED to emptySet(),
         Container.State.UNKNOWN to Container.State.entries.toSet()
      )

      fun builder(): ContainerBuilder<*> {
         return GenericContainerBuilder()
      }
   }

   protected val log: Logger = LoggerFactory.getLogger(javaClass)

   private var runtime: AbstractContainerRuntime = when (builder.containerPlatformType) {
      ContainerPlatformType.DOCKER -> DockerRuntime(this)
      ContainerPlatformType.KUBERNETES -> {
         when (builder.executionMode) {
            ExecutionMode.SERVICE -> K8sServiceRuntime(this)
            ExecutionMode.TASK -> K8sJobRuntime(this)
         }
      }
   }

   private var state: Container.State = Container.State.UNINITIATED

   private val stateLatches: Map<Container.State, CountDownLatch> = mapOf(
      Container.State.INITIALIZING to CountDownLatch(1),
      Container.State.RUNNING to CountDownLatch(1),
      Container.State.TERMINATING to CountDownLatch(1),
      Container.State.STOPPED to CountDownLatch(1),
      Container.State.DELETED to CountDownLatch(1),
      Container.State.FAILED to CountDownLatch(1)
   )

   private val completionLatch: CountDownLatch = CountDownLatch(1)

   override fun getRuntime(): ContainerRuntime {
      return runtime
   }

   override fun getExecutionMode(): ExecutionMode {
      return builder.executionMode
   }

   override fun getName(): ContainerName {
      return builder.name
   }

   override fun getNamespace(): Namespace {
      return builder.namespace
   }

   override fun getLabels(): Map<LabelKey, LabelValue> {
      return builder.labels
   }

   override fun getNetworkName(): NetworkName? {
      return builder.networkName
   }

   override fun getEnv(): Map<EnvVarKey, EnvVarValue> {
      return builder.env
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
      return builder.portMappings[port] ?: port
   }

   override fun getPortMappings(): Map<NetworkPort, NetworkPort> {
      return builder.portMappings
   }

   override fun getVolumes(): List<Volume> {
      return builder.volumes
   }

   override fun isEphemeral(): Boolean {
      return builder.isEphemeral
   }

   override fun getHost(): Host? {
      return runtime.getHost()
   }

   override fun getIpAddress(): InetAddress? {
      return runtime.getIpAddress()
   }

   override fun getDuration(): Duration? {
      return runtime.getDuration()
   }

   override fun getMaxLifeTime(): Duration? {
      return builder.maxLifeTime
   }

   override fun getExitCode(): Int? {
      return runtime.getExitCode()
   }

   override fun getOutputLineCallback(): OutputLineCallback {
      return builder.outputLineCallback
   }

   override fun execute(
      executable: Executable,
      args: Args?,
      useTty: Boolean,
      workingDir: UnixDir?,
      input: InputStream?,
      output: OutputStream,
      waitTimeValue: Long?,
      waitTimeUnit: TimeUnit?,
   ): Pair<Int?, String?> {
      return runtime.execute(
         executable,
         args,
         useTty,
         workingDir,
         input,
         output,
         waitTimeValue,
         waitTimeUnit
      )
   }

   override fun putFile(localFile: Path, remoteDir: UnixDir, remoteFilename: String?): Long {
      return runtime.putFile(localFile, remoteDir, remoteFilename)
   }

   override fun getFile(remoteDir: UnixDir, remoteFilename: String, localPath: Path?): Path {
      return runtime.getFile(remoteDir, remoteFilename, localPath)
   }

   override fun putDirectory(localDir: Path, remoteDir: UnixDir): Long {
      return runtime.putDirectory(localDir, remoteDir)
   }

   override fun getDirectory(remoteDir: UnixDir, localDir: Path): Pair<Path, List<Path>> {
      return runtime.getDirectory(remoteDir, localDir)
   }

   @Synchronized
   override fun getState(): Container.State {
      return state
   }

   override fun waitForState(state: Container.State, timeout: Long, unit: TimeUnit): Boolean {
      val latch = stateLatches[state] ?: throw IllegalArgumentException("Cannot wait for state: $state")
      log.debug("Waiting $timeout $unit for container '${getName()}' to reach state '$state'")

      return when {
         timeout > 0 -> {
            log.debug("Waiting $timeout $unit for container '${getName()}' to reach state '$state'")
            latch.await(timeout, unit)
         }

         else -> {
            log.debug("Waiting indefinitely for container '${getName()}' to reach state '$state'")
            latch.await().let { true }
         }
      }
   }

   override fun waitForCompletion(timeout: Long, unit: TimeUnit): Boolean {
      return if (timeout > 0)
         completionLatch.await(timeout, unit)
      else {
         completionLatch.await()
         true
      }
   }

   override fun toString(): String {
      return ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
         .append("state", state)
         .append("runtime", runtime)
         .append("host", getHost())
         .append("ipAddress", getIpAddress())
         .append("duration", getDuration())
         .append("exitCode", getExitCode())
         .append("builder$NEW_LINE", builder)
         .toString()
   }

   @Synchronized
   internal fun changeState(newState: Container.State, vararg requireOneOf: Container.State) {
      if (getState() == newState) {
         log.debug("Container '${getName()}' is already in state '$newState'")
         return
      }

      requireOneOfStates(*requireOneOf)

      val oldState = state
      state = newState.also {
         log.info("Container '${getName()}' state changed: [$oldState] -> [$newState]")
      }

      // Notify waiting threads
      stateLatches[newState]?.countDown()

      if (isInOneOfStates(Container.State.STOPPED, Container.State.DELETED, Container.State.FAILED)) {
         completionLatch.countDown()
      }
   }

   @Synchronized
   internal fun isInOneOfStates(vararg states: Container.State): Boolean {
      return states.contains(state)
   }

   @Synchronized
   internal fun requireOneOfStates(vararg states: Container.State) {
      if (states.isNotEmpty() && !states.contains(state)) {
         throw ContainerException(
            "Illegal state: container '${getName()}' is in state '${getState()}', " +
               "but required one of '${states.joinToString()}'"
         )
      }
   }

   internal fun isLegalStateChange(oldState: Container.State = getState(), newState: Container.State): Boolean {
      return newState in (LEGAL_STATE_TRANSITIONS[oldState] ?: emptySet())
   }

}