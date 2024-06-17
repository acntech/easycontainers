package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.RegexValidator
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Value object representing a docker/kubernetes label value.
 */
@JvmInline
value class LabelValue(val value: String) : SimpleValueObject<String> {

   companion object {

      // Defines a regular expression pattern that matches any string consisting of printable ASCII characters (ranging from
      // space ' ' to tilde '~') only. An empty string will also be a valid match.
      private val REGEXP: Regex = "^[ -~]*\$".toRegex()

      private val VALIDATOR = StringValidator(
         // https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#syntax-and-character-set
         maxLength = 1063, // This is a practical limit
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): LabelValue {
         return LabelValue(value)
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
