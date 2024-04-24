package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Value object representing a host.
 */
@JvmInline
value class Host(val value: String) : SimpleValueObject<String> {

   companion object {

      const val MIN_LENGTH: Int = 1

      // Need to set this to higher than 63 wince pod native names are too long to concatenate with
      // test.pod.cluster.local
      const val MAX_LENGTH: Int = 100

      private val REGEXP: Regex = "^(?:[a-zA-Z0-9.-]+|\\[[a-fA-F0-9:]+])\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = MIN_LENGTH,
         maxLength = MAX_LENGTH,
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): Host {
         return Host(value)
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
