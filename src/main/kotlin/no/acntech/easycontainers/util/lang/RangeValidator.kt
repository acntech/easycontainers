package no.acntech.easycontainers.util.lang

/**
 * Represents a validation range for a comparable value.
 *
 * @param P the type of the value in the range, must implement Comparable
 * @property inclusiveMin the inclusive minimum value of the range (optional)
 * @property exclusiveMin the exclusive minimum value of the range (optional)
 * @property inclusiveMax the inclusive maximum value of the range (optional)
 * @property exclusiveMax the exclusive maximum value of the range (optional)
 */
data class RangeValidator<P : Comparable<P>>(
   val inclusiveMin: P? = null,
   val exclusiveMin: P? = null,
   val inclusiveMax: P? = null,
   val exclusiveMax: P? = null,
) : Validator<P> {

   init {
      require(!(inclusiveMin != null && exclusiveMin != null)) {
         "Both inclusiveMin '$inclusiveMin' and exclusiveMin '$exclusiveMin' are set. They are mutually exclusive."
      }

      require(!(inclusiveMax != null && exclusiveMax != null)) {
         "Both inclusiveMax '$inclusiveMax' and exclusiveMax '$exclusiveMax' are set. They are mutually exclusive."
      }
   }

   override fun validate(t: P) {
      inclusiveMin?.let { min ->
         if (t < min) {
            throw ValidationException("Value '$t' is below the allowed minimum '$min'")
         }
      }

      exclusiveMin?.let { min ->
         if (t <= min) {
            throw ValidationException("Value '$t' is below the allowed minimum '$min'")
         }
      }

      inclusiveMax?.let { max ->
         if (t > max) {
            throw ValidationException("Value '$t' is above the allowed maximum '$max'")
         }
      }

      exclusiveMax?.let { max ->
         if (t >= max) {
            throw ValidationException("Value '$t' is above the allowed maximum '$max'")
         }
      }
   }

}