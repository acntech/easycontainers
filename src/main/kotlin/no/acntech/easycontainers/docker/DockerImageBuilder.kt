package no.acntech.easycontainers.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.BuildImageResultCallback
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.model.BuildResponseItem
import com.github.dockerjava.api.model.PushResponseItem
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.ImageBuilder
import no.acntech.easycontainers.model.ImageTag
import no.acntech.easycontainers.util.collections.prettyPrint
import no.acntech.easycontainers.util.lang.asStringMap
import no.acntech.easycontainers.util.lang.prettyPrintMe
import no.acntech.easycontainers.util.text.NEW_LINE
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * An implementation of [ImageBuilder] that builds and pushes a Docker image using an accessible Docker daemon either
 * locally or remotely.
 * <p>
 * The presence of the environment variable `DOCKER_HOST` will be used to determine the Docker host to use. If not set,
 * a local daemon is assumed.
 */
internal class DockerImageBuilder(
   private val dockerClient: DockerClient = DockerClientFactory.createDefaultClient(),
) : ImageBuilder() {

   private var startTime: Instant? = null

   private var finishTime: Instant? = null

   override fun getStartTime(): Instant? {
      return startTime
   }

   override fun getFinishTime(): Instant? {
      return finishTime
   }

   override fun buildImage(): Boolean {
      // Preparations for image building
      requireState(State.INITIALIZED)

      if (tags.isEmpty()) {
         tags.add(ImageTag.LATEST)
      }

      startTime = Instant.now()

      changeState(State.IN_PROGRESS)
      log.info("Building Docker image using Docker client: {}", dockerClient)
      log.info("Using build config:\n $this")

      val dockerfile = dockerContextDir.resolve("Dockerfile")
      val tags = createImageTags()

      // Building and tagging the Docker image
      val id = buildAndTagImage(dockerfile, tags)

      log.debug("Built image ID: $id")

      // Pushing the Docker image
      pushImage(tags)
      if (getState() != State.FAILED) {
         changeState(State.COMPLETED)
      }

      finishTime = Instant.now()

      if (getState() == State.COMPLETED) {
         val duration = Duration.between(startTime, finishTime)
         log.info(
            "Image build and push completed successfully in" +
               " ${duration.toMinutes()} minutes and ${duration.toSecondsPart()} seconds"
         )
      } else {
         log.error("Image build and push failed")
      }

      return getState() == State.COMPLETED
   }

   private fun createImageTags(): Set<String> {
      return tags.map { tag -> "${registry}/$repository/$name:$tag" }.toSet()
   }

   private fun buildAndTagImage(dockerfile: Path, tags: Set<String>): String? {
      return try {
         dockerClient.buildImageCmd()
            .withNoCache(true)
            .withDockerfile(dockerfile.toFile())
            .withBaseDirectory(dockerContextDir.toFile())
            .withLabels(labels.entries.associate { (key, value) ->
               key.unwrap() to value.unwrap()
            })
            .withTags(tags)
            .exec(object : BuildImageResultCallback() {

               override fun onNext(item: BuildResponseItem) {
                  log.info("Build response item (progress): {}", item.progressDetail)

                  // Stringify the BuildResponseItem with all its properties
                  log.info("BuildResponseItem: " + item.prettyPrintMe())

                  if (item.isErrorIndicated) {
                     log.error("Error building image: {}", item.errorDetail?.message)
                     changeState(State.FAILED)
                  }

                  if (item.isBuildSuccessIndicated) {
                     log.info("Build success indicated")
                  }
               }

            }).awaitImageId()
      } catch (e: Exception) {
         when (e) {

            is DockerException, is DockerClientException -> {
               // This happens every time, even when the build is successful, hence we must ignore it
               log.error("A docker error occurred during build: {} - IGNORING", e.message, e)
            }

            else -> {
               log.error("An error occurred during docker build, raising ContainerException", e)
               throw ContainerException(e)
            }
         }
         return null
      }
   }

   private fun pushImage(tags: Set<String>) {
      tags.forEach { tag ->
         log.info("Pushing image: $tag")
         dockerClient.pushImageCmd(tag)
            .exec(object : ResultCallback.Adapter<PushResponseItem>() {

               override fun onNext(item: PushResponseItem) {
                  log.info("Push response item: {}", item.toString())
               }

               override fun onComplete() {
                  super.onComplete()
                  log.info("Completed pushing image: $tag")
                  changeState(State.COMPLETED)
               }

               override fun onError(throwable: Throwable) {
                  super.onError(throwable)
                  log.error("Error pushing image: $tag", throwable)
                  if (getState() != State.COMPLETED)
                     changeState(State.FAILED)
               }

            }).awaitCompletion()
      }
   }

   private fun listContainers() {
      val containers = dockerClient.listContainersCmd()
         .withShowSize(true)
         .withShowAll(false)
         .exec()

      log.info("All containers:$NEW_LINE${containers.joinToString(NEW_LINE)}")
   }

}