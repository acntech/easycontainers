package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Value object representing a docker/kubernetes label value.
 */
@JvmInline
value class LabelValue(val value: String) : SimpleValueObject<String> {

   companion object {

      private val REGEXP: Regex = "^[ -~]*\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         // https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#syntax-and-character-set
         maxLength = 1063, // This is a practical limit
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): LabelValue {
         return LabelValue(value)
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
