package no.acntech.easycontainers.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import no.acntech.easycontainers.ImageBuilder
import no.acntech.easycontainers.docker.DockerConstants.DEFAULT_DOCKER_PORT
import no.acntech.easycontainers.docker.DockerConstants.ENV_DOCKER_HOST
import no.acntech.easycontainers.util.text.COLON
import java.time.Instant

/**
 * An implementation of [ImageBuilder] that builds a Docker image using an external and accessible Docker daemon either
 * locally or remotely.
 */
internal class DockerImageBuilder : ImageBuilder {

   private val dockerClient: DockerClient

   private var startTime: Instant? = null

   private var finishTime: Instant? = null

   constructor() {
      var dockerHost = System.getenv(ENV_DOCKER_HOST)

      val configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()

      if (dockerHost != null && dockerHost.isNotEmpty()) {

         if (!dockerHost.startsWith("tcp://")) {
            dockerHost = "tcp://$dockerHost" // Prepend protocol if not present
         }

         if (!dockerHost.contains(COLON)) {
            dockerHost = "$dockerHost:$DEFAULT_DOCKER_PORT" // Append default port if not present
         }

         log.info("Using Docker host: {}", dockerHost)

         configBuilder.withDockerHost(dockerHost)
      }

      dockerClient = DockerClientBuilder.getInstance(configBuilder.build()).build()
   }

   constructor(dockerClient: DockerClient) {
      this.dockerClient = dockerClient
   }

   override fun getStartTime(): Instant? {
      return startTime
   }

   override fun getFinishTime(): Instant? {
      return finishTime
   }

   override fun buildImage(): Boolean {
      // TODO: Implement
//      // Define the path to your Dockerfile and context directory
//      val dockerfile = File(dockerContextDir, "Dockerfile")
//      val contextDir = File(dockerContextDir)
//
//      // Build the Docker image
//      val imageTag = "your_registry_hostname/your_repository_name:your_tag"
//
//      val buildImageCmd: BuildImageCmd = dockerClient.buildImageCmd()
//         .withDockerfile(dockerfile)
//         .withTags(listOf(imageTag))
//         .withBaseDirectory(contextDir)
//
//      buildImageCmd.exec(object : BuildImageResultCallback() {
//
//         override fun onNext(item: BuildResponseItem) {
//            // Process build progress if needed
//         }
//
//      }).awaitImageId()
//
//      // Push the Docker image to the private registry
//      val pushImageCmd: PushImageCmd = dockerClient.pushImageCmd(imageTag)
//
//      // If your private registry requires authentication, you can set it here
//      // pushImageCmd.withAuthConfig(AuthConfig().withUsername("your_username").withPassword("your_password"))
//
//      pushImageCmd.exec(PushImageResultCallback()).awaitSuccess()
      return false
   }

}