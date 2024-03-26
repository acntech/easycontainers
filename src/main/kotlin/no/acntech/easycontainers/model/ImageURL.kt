package no.acntech.easycontainers.model

import no.acntech.easycontainers.util.text.COLON
import no.acntech.easycontainers.util.text.FORWARD_SLASH

/**
 * Represents a Docker image URL consisting of a registry URL, repository name, image name, and tag.
 *
 * @property registryUrl The registry URL of the image.
 * @property repositoryName The repository name of the image.
 * @property imageName The image name.
 * @property tag The image tag. Default is ImageTag.LATEST.
 */
data class ImageURL(
   val registryUrl: RegistryURL,
   val repositoryName: RepositoryName,
   val imageName: ImageName,
   val tag: ImageTag = ImageTag.LATEST,
) {

   companion object {
      fun of(
         registryUrl: String,
         repositoryName: String,
         imageName: String,
         tag: String = ImageTag.LATEST.unwrap(),
      ): ImageURL {
         return ImageURL(
            registryUrl = RegistryURL.of(registryUrl),
            repositoryName = RepositoryName.of(repositoryName),
            imageName = ImageName.of(imageName),
            tag = ImageTag.of(tag)
         )
      }

      fun of(value: String): ImageURL {
         // Parse the value
         val parts = value.split(FORWARD_SLASH)

         require(parts.size > 2) {
            "Invalid Image URL '$value': must include registry URL, repository name, and image name (and optionally a tag)"
         }

         // Create the different parts
         val registryUrl = RegistryURL.of(parts[0])
         val repositoryName = RepositoryName.of(parts[1])

         // Split the image name and tag
         val imageNameAndTag = parts[2]
         val imageName = ImageName.of(imageNameAndTag.substringBefore(COLON))

         // Check if tag is present
         val tag = if (imageNameAndTag.contains(COLON)) {
            ImageTag.of(imageNameAndTag.substringAfter(COLON))
         } else {
            ImageTag.LATEST
         }

         return ImageURL(
            registryUrl = registryUrl,
            repositoryName = repositoryName,
            imageName = imageName,
            tag = tag
         )
      }
   }

   /**
    * Returns the Fully Qualified Domain Name (FQDN) of the image URL.
    * The FQDN is constructed by concatenating the registry URL, repository name, image name, and tag.
    *
    * @return the Fully Qualified Domain Name (FQDN) of the image URL
    */
   fun toFQDN(): String {
      return "${registryUrl.unwrap()}/${repositoryName.unwrap()}/${imageName.unwrap()}:${tag.unwrap()}"
   }

   /**
    * Returns a string representation of the ImageURL, which is the fully qualified domain name (FQDN).
    * The FQDN is constructed by concatenating the registry URL, repository name, image name, and image tag,
    * separated by forward slashes*/
   override fun toString(): String {
      return toFQDN()
   }
}