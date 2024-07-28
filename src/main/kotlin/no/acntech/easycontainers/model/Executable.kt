package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.RegexValidator
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Value object representing an executable, i.e. a command line with optional arguments.
 */
@JvmInline
value class Executable(val value: String) : SimpleValueObject<String> {

   companion object {

      // REGEXP: Defines a regular expression pattern that matches strings consisting of
      // alpha-numeric characters, underscores (_), hyphens (-), periods (.) and forward slashes (/) only
      private val REGEXP: Regex = "^[a-zA-Z0-9_\\-\\.\\/]+$".toRegex()

      private val VALIDATOR = StringValidator(
         minLength = 1,
         maxLength = 255,
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): Executable {
         return Executable(value)
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
