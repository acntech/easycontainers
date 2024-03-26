package no.acntech.easycontainers.model

/**
 * Interface representing the runtime of a container - either Docker or Kubernetes.
 */
interface ContainerRuntime {

   fun getType(): ContainerPlatformType

   fun getContainer(): Container

   fun start()

   fun stop()

   fun kill() {
      stop()
   }

   fun delete(force: Boolean = false)
}