package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Value object representing the common rules for Docker and Kubernetes container names. Note that both container platforms
 * have specific rules beyond these common rules.
 */
@JvmInline
value class ContainerName(val value: String) : SimpleValueObject<String> {


   companion object {

      // Matches strings that start with a lowercase letter or a digit,
      // followed by zero or more lowercase letters, digits, or hyphens,
      // and if there are such, end with a lowercase letter or digit.
      private val REGEXP: Regex = "^[a-z0-9]([-a-z0-9]*[a-z0-9])?\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = 1,
         maxLength = 253,
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): ContainerName {
         return ContainerName(value)
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
