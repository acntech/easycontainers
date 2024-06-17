package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.RegexValidator
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Value object representing a file name for a file to be added or mapped into a container.
 */
@JvmInline
value class ContainerFileName(val value: String) : SimpleValueObject<String> {

   companion object {

      // Matches strings that consist of one or more characters,
      // where each character is a lowercase or uppercase letter, a digit, an underscore, a period, or a hyphen.
      private val REGEXP: Regex = "^[a-zA-Z0-9_.-]+$".toRegex()

      private val VALIDATOR = StringValidator(
         minLength = 1,
         maxLength = 63,
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): ContainerFileName {
         return ContainerFileName(value)
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
