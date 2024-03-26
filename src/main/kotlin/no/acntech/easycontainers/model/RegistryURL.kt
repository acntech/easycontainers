package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Represents a URL of a registry.
 *
 * @param value the value of the registry URL
 */
@JvmInline
value class RegistryURL(val value: String) : SimpleValueObject<String> {

   companion object {

      private val REGEXP: Regex = "^[a-zA-Z0-9]+([.:-][a-zA-Z0-9]+)*(:[0-9]+)?/?\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = 4,
         maxLength = 253,
         lexicalValidator = RegexValidator(REGEXP)
      )

      val DOCKER = RegistryURL("docker.io")
      val KUBERNETES = RegistryURL("k8s.gcr.io")
      val LOCAL = RegistryURL("localhost:5000")

      fun of(value: String): RegistryURL {
         return RegistryURL(value)
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
