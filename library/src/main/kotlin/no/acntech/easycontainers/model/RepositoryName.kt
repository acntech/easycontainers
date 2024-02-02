package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Value object representing a Docker image name.
 */
@JvmInline
value class RepositoryName(val value: String) : SimpleValueObject<String> {

   companion object {

      private val REGEXP: Regex = "^[a-z0-9]+([-_.][a-z0-9]+)*\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = 2,
         maxLength = 255,
         lexicalValidator = RegexValidator(REGEXP)
      )

      val DEFAULT = RepositoryName("default")

      val TEST = RepositoryName("test")

      fun of(value: String): RepositoryName {
         return RepositoryName(value)
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
