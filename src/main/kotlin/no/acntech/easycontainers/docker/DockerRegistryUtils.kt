package no.acntech.easycontainers.docker

import khttp.delete
import khttp.get
import org.json.JSONArray
import org.slf4j.LoggerFactory

/**
 * This object provides utility methods for interacting with a Docker registry.
 */
object DockerRegistryUtils {

   private val log = LoggerFactory.getLogger(DockerRegistryUtils::class.java)

   /**
    * Retrieves the digest of a Docker image from a specified registry using the provided image name and tag.
    *
    * @param registryUrl The URL of the Docker registry.
    * @param imageName The name of the Docker image.
    * @param tag The tag of the Docker image.
    * @return The digest of the Docker image, or null if the operation fails or the digest is missing.
    */
   fun getImageDigest(registryUrl: String, imageName: String, tag: String): String? {
      val response = get(
         url = "$registryUrl/v2/$imageName/manifests/$tag",
         headers = mapOf("Accept" to "application/vnd.docker.distribution.manifest.v2+json")
      )

      if (!response.statusCode.isSuccess()) {
         log.warn("Failed to get digest for image '$imageName:$tag' at '$registryUrl': ${response.text}")
         return null
      }

      val digestHeader = response.headers["Docker-Content-Digest"]
      if (digestHeader.isNullOrBlank()) {
         log.warn("Missing or empty 'Docker-Content-Digest' header.")
         return null
      }

      val digestParts = digestHeader.split(':')
      if (digestParts.size != 2) {
         log.warn("Invalid 'Docker-Content-Digest' header.")
         return null
      }

      // We only need the digest part, which is the second element in the array
      return digestParts[1]
   }

   /**
    * Deletes an image from a registry.
    *
    * @param registryUrl The URL of the registry.
    * @param imageName The name of the image.
    * @param digest The digest of the image (optional). If provided, the image with the specified digest will be deleted.
    * @param tags The list of tags associated with the image (optional). If no digest is provided, all images with the specified tags will be deleted.
    */
   fun deleteImage(registryUrl: String, imageName: String, digest: String? = null, tags: List<String> = emptyList()) {
      var actualTags: List<String> = tags
      if (digest != null) {
         internalDeleteImage(registryUrl, imageName, digest)
      } else if (actualTags.isEmpty()) {
         actualTags = getAllImageTags(registryUrl, imageName)
      }

      actualTags.forEach { tag ->
         getImageDigest(registryUrl, imageName, tag)?.let { tagDigest ->
            internalDeleteImage(registryUrl, imageName, tagDigest)
         }
      }
   }

   /**
    * Retrieves all image tags for a given registry URL and image name.
    *
    * @param registryUrl The URL of the registry.
    * @param imageName The name of the image.
    * @return The list of image tags as strings.
    */
   fun getAllImageTags(registryUrl: String, imageName: String): List<String> {
      val response = get("$registryUrl/v2/$imageName/tags/list")
      if (!response.statusCode.isSuccess()) {
         log.warn("Failed to list tags for image '$imageName': ${response.text}")
         return emptyList()
      }

      val jsonResponse = JSONArray(response.jsonObject.getJSONArray("tags"))
      return jsonResponse.map { it.toString() }
   }

   private fun internalDeleteImage(registryUrl: String, imageName: String, digest: String): Boolean {
      if (registryUrl.isBlank() || imageName.isBlank() || digest.isBlank()) {
         log.warn("Invalid parameters for deleting image. Registry URL: '$registryUrl', Image Name: '$imageName', Digest: '$digest'")
         return false
      }

      val deleteUrl = buildDeleteImageUrl(registryUrl, imageName, digest)
      return try {
         val response = delete(deleteUrl)
         if (response.statusCode.isSuccess()) {
            log.info("Image '$imageName' with digest '$digest' deleted successfully from '$registryUrl'.")
            true
         } else {
            log.warn("Failed to delete image '$imageName' with digest '$digest' from '$registryUrl': ${response.text}")
            false
         }
      } catch (e: Exception) {
         log.error("Exception occurred while deleting image '$imageName' with digest '$digest' from '$registryUrl'", e)
         false
      }
   }

   private fun buildDeleteImageUrl(registryUrl: String, imageName: String, digest: String): String {
      return "$registryUrl/v2/$imageName/manifests/$digest"
   }

   private fun Int.isSuccess() = this in 200..299
}


