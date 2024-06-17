package no.acntech.easycontainers.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.okhttp.OkDockerHttpClient
import com.github.dockerjava.transport.DockerHttpClient
import no.acntech.easycontainers.Environment
import org.slf4j.LoggerFactory

/**
 * The DockerClientFactory class is responsible for creating a DockerClient instance.
 * It provides a method to create a default DockerClient using the Docker host configuration.
 */
object DockerClientFactory {

   private val log = LoggerFactory.getLogger(DockerClientFactory::class.java)

   /**
    * This method creates a default Docker client.
    *
    * @return The default DockerClient instance.
    */
   fun createDefaultClient(): DockerClient {
      val dockerHost = getDockerHost()

      val dockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
         .withDockerHost(dockerHost)
         .build()

      val httpClient: DockerHttpClient = OkDockerHttpClient.Builder()
         .dockerHost(dockerClientConfig.dockerHost)
         .sslConfig(dockerClientConfig.sslConfig)
         .connectTimeout(10_000) // in milliseconds
         .readTimeout(60_000) // in milliseconds
         .build()

      return DockerClientBuilder.getInstance(dockerClientConfig)
         .withDockerHttpClient(httpClient)
         .build().also {
            log.info("Docker client created: $it")
         }
   }

   /**
    * Retrieves the Docker host from environment variables or system properties.
    *
    * @return The Docker host as a String, or null if not found.
    */
   private fun getDockerHost(): String {
      return Environment.defaultDockerDaemonEndpoint

//      var dockerHost = System.getenv(DockerConstants.ENV_DOCKER_DAEMON_ENDPOINT)
//
//      if (dockerHost.isEmpty()) {
//         dockerHost = System.getProperty(Environment.PROP_DOCKER_DAEMON_ENDPOINT, EMPTY_STRING)
//      }
//
//      if (dockerHost.isNotEmpty()) {
//         log.debug("Using Docker host from environment/system-property variable: $dockerHost")
//         if (!dockerHost.matches(".*:\\d+".toRegex())) { // host:port
//            dockerHost += ":${DockerConstants.DEFAULT_DOCKER_TCP_INSECURE_PORT}"
//         }
//      }
//
//      return dockerHost.also {
//         log.debug("Using Docker host: $dockerHost")
//      }
   }

}