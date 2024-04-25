package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Value object representing a kubernetes namespace.
 */
@JvmInline
value class Namespace(val value: String) : SimpleValueObject<String> {

   companion object {

      // Defines a regular expression pattern for validating Kubernetes Namespace names.
      // According to Kubernetes documentation, a valid Namespace consists of alphanumeric characters, dashes (-), and must
      // start and end with an alphanumeric character. The '+' in the pattern indicates that one or more of the allowed
      // characters must be present.
      private val REGEXP: Regex = "^[a-z0-9]([-a-z0-9]*[a-z0-9])?\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = 1,
         maxLength = 63, // Kubernetes Namespace names are limited to 63 characters.
         lexicalValidator = RegexValidator(REGEXP)
      )

      val DEFAULT = Namespace("default")

      val TEST = Namespace("test")

      fun of(value: String): Namespace {
         return Namespace(value)
      }
   }

   init {
      VALIDATOR.validate(this)
   }

   override fun unwrap(): String {
      return value
   }

}
