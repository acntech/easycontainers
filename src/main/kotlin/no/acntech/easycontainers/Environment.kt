package no.acntech.easycontainers

import no.acntech.easycontainers.docker.DockerConstants
import no.acntech.easycontainers.util.net.NetworkUtils
import no.acntech.easycontainers.util.platform.PlatformUtils
import no.acntech.easycontainers.util.text.EMPTY_STRING
import org.slf4j.LoggerFactory

object Environment {

   private val log = LoggerFactory.getLogger(Environment::class.java)

   const val PROP_DOCKER_DAEMON_ENDPOINT = "easycontainers.docker.host"
   const val PROP_REGISTRY_ENDPOINT = "easycontainers.registry.endpoint"
   const val PROP_INSECURE_REGISTRY = "easycontainers.registry.insecure"

   const val PROP_K8S_KANIKO_DATA_HOST_DIR = "easycontainers.k8s.kaniko-data.host.dir"
   const val PROP_K8S_KANIKO_DATA_PVC_NAME = "easycontainers.k8s.kaniko-data.pvc.name"

   const val PROP_K8S_GENERAL_DATA_HOST_DIR = "easycontainers.k8s.general-data.host.dir"
   const val PROP_K8S_GENERAL_DATA_PVC_NAME = "easycontainers.k8s.general-data.pvc.name"

   const val DEFAULT_K8S_KANIKO_DATA_HOST_DIR = "/home/thomas/kind/kaniko-data"
   const val DEFAULT_K8S_KANIKO_DATA_PVC_NAME = "kaniko-data-pvc"

   const val DEFAULT_K8S_GENERAL_DATA_HOST_DIR = "/home/thomas/kind/share"
   const val DEFAULT_K8S_GENERAL_DATA_PVC_NAME = "host-share-pvc"

   const val DEFAULT_REGISTRY_HOST = "localhost"
   const val DEFAULT_REGISTRY_PORT = 5000

   // Note that this includes the protocol
   val defaultRegistryCompleteURL: String // e.g. http://localhost:5000

   val defaultRegistryEndpoint: String // e.g. localhost:5000

   val defaultDockerDaemonEndpoint: String // e.g. tcp://localhost:2375, tcps://docker.acne.com:2376, unix:///var/run/docker.sock

   val k8sKanikoDataHostDir: String = System.getProperty(PROP_K8S_KANIKO_DATA_HOST_DIR, DEFAULT_K8S_KANIKO_DATA_HOST_DIR)

   val k8sKanikoDataPvcName: String = System.getProperty(PROP_K8S_KANIKO_DATA_PVC_NAME, DEFAULT_K8S_KANIKO_DATA_PVC_NAME)

   val k8sGeneralDataHostDir: String = System.getProperty(PROP_K8S_GENERAL_DATA_HOST_DIR, DEFAULT_K8S_GENERAL_DATA_HOST_DIR)

   val k8sGeneralDataPvcName: String = System.getProperty(PROP_K8S_GENERAL_DATA_PVC_NAME, DEFAULT_K8S_GENERAL_DATA_PVC_NAME)

   init {
      defaultRegistryCompleteURL = extractRegistryUrl().also {
         log.info("Environment: Default registry URL: $it")
      }

      // Set default registry endpoint from the url - extract the host and port
      defaultRegistryEndpoint = defaultRegistryCompleteURL.substringAfter("://").also {
         log.info("Environment: Default registry endpoint: $it")
      }

      defaultDockerDaemonEndpoint = extractDockerDaemonEndpoint().also {
         log.info("Environment: Default Docker daemon endpoint: $it")
      }
   }

   private fun extractDockerDaemonEndpoint(): String {
      val dockerHost = System.getenv(DockerConstants.ENV_DOCKER_DAEMON_ENDPOINT)
      var result = EMPTY_STRING

      if (!dockerHost.isNullOrEmpty()) {
         result = if (dockerHost.startsWith("tcp") && !dockerHost.matches(".*:\\d+".toRegex())) // host:port
            "$dockerHost:${DockerConstants.DEFAULT_DOCKER_TCP_INSECURE_PORT}"
         else dockerHost
      }

      if (result.isEmpty()) {
         val endpoint = System.getProperty(PROP_DOCKER_DAEMON_ENDPOINT)
         if (!endpoint.isNullOrEmpty()) {
            result = endpoint
         }
      }

      if (result.isEmpty()) {
         val host = PlatformUtils.getWslIpAddress() ?: "localhost"
         result = getEndpointByOpenPort(host, DockerConstants.DEFAULT_DOCKER_TCP_INSECURE_PORT, "tcp")
            ?: getEndpointByOpenPort(host, DockerConstants.DEFAULT_DOCKER_TCP_SECURE_PORT, "tcps")
               ?: DockerConstants.DEFAULT_DOCKER_DAEMON_ENDPOINT
      }

      return result
   }

   private fun getEndpointByOpenPort(host: String, port: Int, protocol: String): String? {
      if (NetworkUtils.isTcpPortOpen(host, port)) {
         val endPoint = "$protocol://$host:$port"
         System.setProperty(PROP_DOCKER_DAEMON_ENDPOINT, endPoint)
         return endPoint
      }
      return null
   }

   private fun extractRegistryUrl(): String {
      val result: String

      val registryEndpoint = System.getProperty(PROP_REGISTRY_ENDPOINT)

      if (!registryEndpoint.isNullOrEmpty()) {
         val insecureRegistry = System.getProperty(PROP_INSECURE_REGISTRY).toBoolean()
         result = if (insecureRegistry) {
            "http://$registryEndpoint"
         } else {
            "https://$registryEndpoint"
         }
      } else {
         val registryHost = PlatformUtils.getWslIpAddress() ?: DEFAULT_REGISTRY_HOST
         result = "http://$registryHost:$DEFAULT_REGISTRY_PORT"
      }

      return result
   }

}