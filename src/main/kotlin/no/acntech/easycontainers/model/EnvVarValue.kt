package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.RegexValidator
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Value object representing an environment variable value.
 */
@JvmInline
value class EnvVarValue(val value: String) : SimpleValueObject<String> {

   companion object {

      // REGEXP: Defines a regular expression pattern that matches any string consisting of printable ASCII characters
      // (ranging from space ' ' to tilde '~') only. An empty string will also be a valid match.
      private val REGEXP: Regex = "^[ -~]*\$".toRegex()

      private val VALIDATOR = StringValidator(
         minLength = 0,
         maxLength = 255, // Reasonable limit for environment variable value
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): EnvVarValue {
         return EnvVarValue(value)
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
