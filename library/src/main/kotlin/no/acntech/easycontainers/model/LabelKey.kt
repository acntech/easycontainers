package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Value object representing a docker/kubernetes label key.
 */
@JvmInline
value class LabelKey(val value: String) : SimpleValueObject<String> {

   companion object {

      private val REGEXP: Regex = "^[a-zA-Z0-9_-]\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = 1,
         maxLength = 63,
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): LabelKey {
         return LabelKey(value)
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
