package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.StringValueObjectValidator
import no.acntech.easycontainers.util.lang.RegexValidator

/**
 * Represents a volume name.
 *
 * A volume name is a string value object that holds the name of a volume to be added or mapped into a container.
 *
 * The volume name must comply with the following rules:
 * - The length of the name must be between 1 and 63 characters, inclusive.
 * - The name can only contain alphanumeric characters, underscores (_), and hyphens (-).
 *
 * The volume name is validated upon construction using the StringValueObjectValidator class.
 *
 * The VolumeName class provides the following functionality:
 * - Construction of a VolumeName object using the companion object's `of` function.
 * - Unwrapping the volume name value using the `unwrap` function.
 * - Converting the volume name to a string representation using the `toString` function.
 *
 * @property value The underlying string value of the volume name.
 *
 * @see SimpleValueObject
 * @see StringValueObjectValidator
 */
@JvmInline
value class VolumeName(val value: String) : SimpleValueObject<String> {

   companion object {

      private val REGEXP: Regex = "^[a-zA-Z0-9_-]\$".toRegex()

      private val VALIDATOR = StringValueObjectValidator(
         minLength = 1,
         maxLength = 63,
         lexicalValidator = RegexValidator(REGEXP)
      )

      fun of(value: String): VolumeName {
         return VolumeName(value)
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
