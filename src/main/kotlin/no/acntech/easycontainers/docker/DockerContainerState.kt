package no.acntech.easycontainers.docker

internal enum class DockerContainerState {
   CREATED,
   RESTARTING,
   RUNNING,
   REMOVING,
   PAUSED,
   EXITED,
   DEAD
}
