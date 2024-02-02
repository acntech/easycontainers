package no.acntech.easycontainers.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.PullResponseItem
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import no.acntech.easycontainers.AbstractContainer
import no.acntech.easycontainers.ContainerBuilder
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.model.Container

internal class DockerContainer(
   containerBuilder: ContainerBuilder,
) : AbstractContainer(containerBuilder) {

   private val dockerClient: DockerClient

   private var containerId: String? = null

   init {
      val configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()

      val dockerHost = System.getenv(DockerConstants.ENV_DOCKER_HOST)
      if (dockerHost != null && dockerHost.isNotEmpty()) {
         log.info("Using Docker host from environment variable: {}", dockerHost)
         configBuilder.withDockerHost(dockerHost)
      }
      dockerClient = DockerClientBuilder.getInstance(configBuilder.build()).build()
   }

   override fun run() {
      changeState(Container.State.STARTED)
      pullImage()
      startContainer()
      logOutput() // Blocking
   }

   override fun stop() {
      try {
         dockerClient.stopContainerCmd(containerId!!).exec()
         log.info("Container successfully stopped: $containerId")
         changeState(Container.State.STOPPED)

      } catch (e: Exception) {
         when (e) {
            is DockerException, is DockerClientException -> {
               log.error("Failed to stop container '${builder.name}', raising ContainerException", e)
               changeState(Container.State.FAILED)
               throw ContainerException("Error '${e.message} stopping container ${builder.name}", e)
            }

            else -> {
               log.error("Failed to stop container '${builder.name}', re-throwing", e)
               changeState(Container.State.FAILED)
               throw e
            }
         }
      }
   }

   override fun remove() {
      try {
         dockerClient.removeContainerCmd(containerId!!)
            .withForce(true)
            .exec()
         log.info("Container successfully removed: $containerId")
         changeState(Container.State.REMOVED)

      } catch (e: Exception) {
         when (e) {
            is DockerException, is DockerClientException -> {
               log.error("Failed to remove container '${builder.name}', raising ContainerException", e)
               changeState(Container.State.FAILED)
               throw ContainerException("Error '${e.message} removing container ${builder.name}", e)
            }

            else -> {
               log.error("Failed to remove container '${builder.name}', re-throwing", e)
               changeState(Container.State.FAILED)
               throw e
            }
         }
      }
   }

   private fun pullImage() {
      try {
         dockerClient.pullImageCmd(builder.image.toFQDN())
            .exec(object : PullImageResultCallback() {

               override fun onNext(item: PullResponseItem?) {
                  log.info("Pulling image: ${item?.status}")
                  super.onNext(item)
               }

            }).awaitCompletion()

         log.info("Image pulled successfully: ${builder.image}")

      } catch (e: Exception) {
         when (e) {
            is DockerException, is DockerClientException -> {
               log.error("Failed to pull image '${builder.image}', raising ContainerException", e)
               changeState(Container.State.FAILED)
               throw ContainerException("Error '${e.message} pulling image ${builder.image}", e)
            }

            else -> {
               log.error("Failed to pull image '${builder.image}', re-throwing", e)
               changeState(Container.State.FAILED)
               throw e
            }
         }
      }
   }

   private fun startContainer() {
      try {
         val image = builder.image.toFQDN()

         containerId = dockerClient.createContainerCmd(image)
            .withName(builder.name.unwrap())
            .withEnv(builder.env.toMap().map { "${it.key.unwrap()}=${it.value.unwrap()}" })
            .withLabels(builder.labels.map { (key, value) -> key.unwrap() to value.unwrap() }.toMap())
            .apply {
               val commandParts = mutableListOf<String>()
               builder.command?.let { commandParts.addAll(it.value.split("\\s".toRegex())) }
               builder.args?.let { commandParts.addAll(it.toStringList()) }
               withCmd(commandParts).also {
                  log.info("Running container using command: $commandParts")
               }
            }
            .exec()
            .id

         dockerClient.startContainerCmd(containerId!!).exec()
         log.info("Container successfully started: $containerId")
         changeState(Container.State.RUNNING)

      } catch (e: Exception) {
         when (e) {
            is DockerException, is DockerClientException -> {
               log.error("Failed to start container '${builder.name}', raising ContainerException", e)
               changeState(Container.State.FAILED)
               throw ContainerException("Error '${e.message} starting container ${builder.name}", e)
            }

            else -> {
               log.error("Failed to start container '${builder.name}', re-throwing", e)
               changeState(Container.State.FAILED)
               throw e
            }
         }
      }
   }

   private fun logOutput() {
      dockerClient.logContainerCmd(containerId!!)
         .withStdOut(true)
         .withStdErr(true)
         .withFollowStream(true)
         .withTailAll()
         .exec(object : ResultCallback.Adapter<Frame>() {

            override fun onNext(item: Frame) {
               val line = item.payload.decodeToString()
               log.info("Container [${builder.name}] output: $line")
               builder.lineCallback.onLine(line)
            }

            override fun onError(throwable: Throwable) {
               log.warn("Container [${builder.name}] output error", throwable)
            }

            override fun onComplete() {
               log.info("Container [${builder.name}] output complete")
            }

         }).awaitCompletion()
   }


}