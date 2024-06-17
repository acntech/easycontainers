package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.RegexValidator
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Value object representing an environment variable key.
 */
@JvmInline
value class EnvVarKey(val value: String) : SimpleValueObject<String> {

   companion object {

      // REGEXP: Defines a regular expression pattern to match valid identifiers
      // An identifier must start with a letter (a-z or A-Z) or an underscore (_),
      // followed by zero or more alphanumeric characters (a-z, A-Z, 0-9) or underscores (_)
      private val REGEXP: Regex = "^[a-zA-Z_][a-zA-Z0-9_]*\$".toRegex()

      private val VALIDATOR = StringValidator(
         minLength = 1,
         maxLength = 255,
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): EnvVarKey {
         return EnvVarKey(value)
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
