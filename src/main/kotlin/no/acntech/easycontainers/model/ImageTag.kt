package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.RegexValidator
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Value object representing a Docker image tag.
 */
@JvmInline
value class ImageTag(val value: String) : SimpleValueObject<String> {

   companion object {

      // Defines a regular expression pattern that matches any string composed solely of alpha-numeric characters
      // (a-z, A-Z, 0-9), underscores (_), hyphens (-), and periods (.).
      // The + at the end signifies that one or more of these characters must be present for a match.
      private val REGEXP: Regex = "^[a-zA-Z0-9_\\-\\.]+$".toRegex()

      private val VALIDATOR = StringValidator(
         minLength = 2,
         maxLength = 255,
         lexicalValidator = RegexValidator(REGEXP)
      )

      val LATEST = ImageTag("latest")

      fun of(value: String): ImageTag {
         return ImageTag(value)
      }
   }

   init {
      VALIDATOR.validate(this.value)
   }

   override fun unwrap(): String {
      return value
   }

   override fun toString(): String {
      return value
   }

}
