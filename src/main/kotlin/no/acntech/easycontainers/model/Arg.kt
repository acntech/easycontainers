package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Value object representing a kubernetes/docker command argument.
 */
@JvmInline
value class Arg(val value: String) : SimpleValueObject<String> {

   companion object {

      private val REGEXP: Regex = "^[a-zA-Z0-9_\\-.]+\$".toRegex()

      private val VALIDATOR = StringValidator(
         minLength = 1,
         maxLength = 255,
//         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): Arg {
         return Arg(value)
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
