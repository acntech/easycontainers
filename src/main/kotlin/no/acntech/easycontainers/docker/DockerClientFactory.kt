package no.acntech.easycontainers.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import no.acntech.easycontainers.util.text.EMPTY_STRING
import org.slf4j.LoggerFactory

object DockerClientFactory {

   private val log = LoggerFactory.getLogger(DockerClientFactory::class.java)

   fun createDefaultClient(): DockerClient {
      val configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()

      var dockerHost = System.getenv(DockerConstants.ENV_DOCKER_HOST)

      if (dockerHost.isEmpty()) {
         dockerHost = System.getProperty(DockerConstants.PROP_DOCKER_HOST, EMPTY_STRING)
      }

      if (dockerHost.isNotEmpty()) {
         log.debug("Using Docker host from environment/system-property variable: $dockerHost")
         if (!dockerHost.matches(".*:\\d+".toRegex())) { // host:port
            dockerHost += ":${DockerConstants.DEFAULT_DOCKER_TCP_PORT}"
         }
         log.info("Using Docker host: $dockerHost")
         configBuilder.withDockerHost(dockerHost)
      }

      return DockerClientBuilder.getInstance(configBuilder.build()).build().also {
         log.info("Docker client created: $it")
      }

   }

}