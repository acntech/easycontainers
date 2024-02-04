package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Value object representing a file name for a file to be added or mapped into a container.
 */
@JvmInline
value class ContainerFileName(val value: String) : SimpleValueObject<String> {

   companion object {

      private val REGEXP: Regex = "^[a-zA-Z0-9_-]\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = 1,
         maxLength = 63,
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): ContainerFileName {
         return ContainerFileName(value)
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
