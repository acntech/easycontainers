package no.acntech.easycontainers.docker

object DockerConstants {

   const val ENV_DOCKER_HOST = "DOCKER_HOST"

   const val PROP_DOCKER_HOST = "docker.host"

   const val DEFAULT_DOCKER_HOST = "unix:///var/run/docker.sock"

   const val DEFAULT_DOCKER_TCP_PORT = 2375

}