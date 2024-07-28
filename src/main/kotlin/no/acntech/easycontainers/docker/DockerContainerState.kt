package no.acntech.easycontainers.docker

/**
 * Represents the state of a Docker container.
 */
internal enum class DockerContainerState {
   CREATED,
   RESTARTING,
   RUNNING,
   REMOVING,
   PAUSED,
   EXITED,
   DEAD
}
