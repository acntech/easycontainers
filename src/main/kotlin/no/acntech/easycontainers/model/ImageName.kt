package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Value object representing a Docker (registry) image name.
 */
@JvmInline
value class ImageName(val value: String) : SimpleValueObject<String> {

   companion object {

      // REGEXP: Matches strings starting with one or more lowercase letters or digits,
      // optionally followed by groups of one character (-, _, or .) and one or more lowercase letters or digits.
      private val REGEXP: Regex = "^[a-z0-9]+([-_.][a-z0-9]+)*\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = 2,
         maxLength = 255,
         lexicalValidator = RegexValidator(REGEXP)
      )

      val DEFAULT = ImageName("my-image")

      fun of(value: String): ImageName {
         return ImageName(value)
      }
   }

   init {
      VALIDATOR.validate(this)
   }

   override fun unwrap(): String {
      return value
   }

   override fun toString(): String {
      return value
   }

}
