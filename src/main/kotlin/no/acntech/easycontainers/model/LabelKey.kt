package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.RegexValidator
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Value object representing a docker/kubernetes label key.
 */
@JvmInline
value class LabelKey(val value: String) : SimpleValueObject<String> {

   companion object {

      // Defines a regular expression pattern that matches a single character string. The character can be an alpha-numeric
      // value (a-z, A-Z, 0-9), an underscore (_), or a hyphen (-).
      private val REGEXP: Regex = "^[a-zA-Z0-9_-]\$".toRegex()

      private val VALIDATOR = StringValidator(
         minLength = 1,
         maxLength = 63,
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): LabelKey {
         return LabelKey(value)
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
