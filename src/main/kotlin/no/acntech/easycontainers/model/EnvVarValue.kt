package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Value object representing an environment variable value.
 */
@JvmInline
value class EnvVarValue(val value: String) : SimpleValueObject<String> {

   companion object {

      private val REGEXP: Regex = "^[ -~]*\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = 0,
         maxLength = 255, // Reasonable limit for environment variable value
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): EnvVarValue {
         return EnvVarValue(value)
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
