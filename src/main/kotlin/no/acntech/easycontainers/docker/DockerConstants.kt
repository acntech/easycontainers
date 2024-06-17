package no.acntech.easycontainers.docker

object DockerConstants {

   const val PROP_ENABLE_NATIVE_DOCKER_ENTRYPOINT_STRATEGY = "enableNativeDockerEntrypointStrategy"

   const val ENV_DOCKER_DAEMON_ENDPOINT = "DOCKER_HOST"

   const val NETWORK_MODE_BRIDGE = "bridge"
   const val NETWORK_MODE_HOST = "host"
   const val NETWORK_MODE_NONE = "none"
   const val NETWORK_NODE_CONTAINER = "container:"

   const val DEFAULT_BRIDGE_NETWORK = "bridge"
   const val DEFAULT_DOCKER_API_VERSION = "1.45"
   const val DEFAULT_DOCKER_DAEMON_ENDPOINT = "unix:///var/run/docker.sock"
   const val DEFAULT_DOCKER_TCP_INSECURE_PORT = 2375
   const val DEFAULT_DOCKER_TCP_SECURE_PORT = 2376

}