package no.acntech.easycontainers.model

interface Container {

   enum class State {
      CREATED,
      STARTED,
      RUNNING,
      FAILED,
      UNKNOWN,
      STOPPED,
      REMOVED
   }

   fun getState(): State

   // Name

   fun getName(): ContainerName

   fun getNamespace(): Namespace

   fun getLabels(): Map<LabelKey, LabelValue>

   // Environment

   fun getEnv(): Map<EnvVarKey, EnvVarValue>

   // Command

   fun getCommand(): Executable?

   fun getArgs(): Args?

   // Image

   fun getImage(): ImageURL

   // Ports

   fun getExposedPorts(): List<NetworkPort>

   fun getMappedPort(port: NetworkPort): NetworkPort

   fun getPortMappings(): Map<NetworkPort, NetworkPort>

   fun hasPortMapping(port: NetworkPort): Boolean {

      return getPortMappings().containsKey(port)
   }

   // Ephemerality
   fun isEphemeral(): Boolean

   // Host
   fun getHost(): Host?

   // Lifecycle

   fun start()

   fun stop()

   fun remove()

}