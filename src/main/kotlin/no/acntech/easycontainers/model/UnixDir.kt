package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.RegexValidator
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Represents a complete (starting with root '/') Unix directory path as a value object.
 *
 * @param value The underlying String value representing the directory path.
 */
@JvmInline
value class UnixDir(val value: String) : SimpleValueObject<String> {

   companion object {

      // REGEXP: Defines a regular expression pattern for validating Unix directory paths.
      // A valid Unix directory path should start with a '/' (root directory) followed by any number of subdirectories
      // separated by '/'. Each subdirectory must have at least one character and cannot contain a '/'.
      // The pattern doesn't accept paths that have consecutive '/' (bad format) or end with '/'
      private val REGEXP: Regex = "^/([^/]+(/[^/]*)*)?$".toRegex()

      private val VALIDATOR = StringValidator(
         minLength = 1,
         maxLength = 1024, // Reasonable limit for unix path
         lexicalValidator = RegexValidator(REGEXP)
      )

      val ROOT = UnixDir("/")
      val ETC = UnixDir("/etc")
      val HOME = UnixDir("/home")
      val TMP = UnixDir("/tmp")
      val MNT = UnixDir("/mnt")

      fun of(value: String): UnixDir {
         return UnixDir(value)
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
