package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.RegexValidator
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Value object representing a Docker image tag.
 */
@JvmInline
value class NetworkName(val value: String) : SimpleValueObject<String> {

   companion object {

      // Defines a regular expression pattern for validating network names.
      // A valid network name must start and end with an alpha-numeric character (a-z, A-Z, 0-9).
      // The middle portion of the name can include alpha-numeric characters, underscores (_), dots (.) or hyphens (-).
      private val REGEXP: Regex = "^[a-zA-Z0-9][a-zA-Z0-9_.-]*[a-zA-Z0-9]\$".toRegex()

      private val VALIDATOR = StringValidator(
         minLength = 2,
         maxLength = 255,
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): NetworkName {
         return NetworkName(value)
      }
   }

   init {
      VALIDATOR.validate(this.unwrap())
   }

   override fun unwrap(): String {
      return value
   }

   override fun toString(): String {
      return value
   }

}
