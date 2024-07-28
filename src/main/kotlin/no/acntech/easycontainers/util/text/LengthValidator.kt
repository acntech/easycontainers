package no.acntech.easycontainers.util.text

import no.acntech.easycontainers.util.lang.ValidationException
import no.acntech.easycontainers.util.lang.Validator

/**
 * A validator that forces a string to be of a certain length.
 *
 * @property min The minimum length of the string.
 * @property max The maximum length of the string.
 */
data class LengthValidator(val min: Int?, val max: Int?) : Validator<String> {

   override fun validate(t: String) {
      if (min != null && t.length < min) {
         throw ValidationException("String '${t.truncate(32)}' is shorter at #${t.length} than the required minimum length of $min")
      }
      if (max != null && t.length > max) {
         throw ValidationException("String '${t.truncate(32)}' is longer #${t.length} than the required maximum length of $max")
      }
   }
}
