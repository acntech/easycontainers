package no.acntech.easycontainers.util.text

import no.acntech.easycontainers.util.lang.ValidationException
import no.acntech.easycontainers.util.lang.Validator

/**
 * A validator that uses regular expressions to validate strings.
 *
 * @property pattern The regular expression pattern used for validation.
 */
class RegexValidator(val pattern: Regex) : Validator<String> {

   override fun validate(t: String) {
      if (!pattern.matches(t)) {
         throw ValidationException("String '$t' does not match the required regex pattern: ${pattern.pattern}")
      }
   }

   override fun equals(other: Any?): Boolean {
      return other is RegexValidator && pattern == other.pattern
   }

   override fun hashCode(): Int {
      return pattern.hashCode()
   }

   override fun toString(): String {
      return pattern.pattern
   }
}
