package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.RegexValidator
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Represents a URL of a registry.
 *
 * @param value the value of the registry URL
 */
@JvmInline
value class RegistryURL(val value: String) : SimpleValueObject<String> {

   companion object {

      // REGEXP: Defines a regular expression pattern for validating Registry URLs.
      // A valid Registry URL should begin with one or more alphanumeric characters (a-z, A-Z, 0-9), followed by any number of
      // groups consisting of a '.', ':', or '-' and one or more alphanumeric characters. The URL may optionally end with a ':'
      // followed by one or more digits (representing a port number) followed by an optional '/'.
      private val REGEXP: Regex = "^[a-zA-Z0-9]+([.:-][a-zA-Z0-9]+)*(:[0-9]+)?/?\$".toRegex()

      private val VALIDATOR = StringValidator(
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
      VALIDATOR.validate(this.unwrap())
   }

   override fun unwrap(): String {
      return value
   }

   override fun toString(): String {
      return value
   }

}
