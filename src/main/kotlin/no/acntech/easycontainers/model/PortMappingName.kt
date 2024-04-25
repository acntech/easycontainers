package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Value object representing a port mapping name
 */
@JvmInline
value class PortMappingName(val value: String) : SimpleValueObject<String> {

   companion object {

      // REGEXP: Defines a regular expression pattern for validating port mapping names.
      // A valid port mapping name starts and ends with an alphanumeric character (a-z, A-Z, 0-9) and
      // can have dashes (-) in the middle. The name cannot start or end with a dash (-).
      private val REGEXP: Regex = "^[a-zA-Z0-9]([-a-zA-Z0-9]*[a-zA-Z0-9])?\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = 1,
         maxLength = 15,
         lexicalValidator = RegexValidator(REGEXP)
      )

      val HTTP = PortMappingName("http")
      val HTTPS = PortMappingName("https")
      val TRANSPORT = PortMappingName("transport")
      val SSH = PortMappingName("ssh")

      fun of(value: String): PortMappingName {
         return PortMappingName(value)
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
