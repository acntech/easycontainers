package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Value object representing a file name for a file to be added or mapped into a container.
 */
@JvmInline
value class SemanticVersion(val value: String) : SimpleValueObject<String> {

   companion object {

      // REGEXP: Matches semantic versions with major, minor, and patch numbers followed by optional pre-release identifiers
      // and build metadata. A version looks like this: [major].[minor].[patch]-[pre-release]+[build]
      private val REGEXP: Regex = "^([0-9]+)\\.([0-9]+)\\.([0-9]+)(-[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*)?(\\+[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*)?$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = 1,
         maxLength = 127,
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): SemanticVersion {
         return SemanticVersion(value)
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
