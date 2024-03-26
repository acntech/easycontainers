package no.acntech.easycontainers.model.base

import no.acntech.easycontainers.util.lang.ValidationException
import no.acntech.easycontainers.util.lang.Validator

/**
 * Class that validates a SimpleValueObject based on a given range.
 *
 * @param P the type of the value in the SimpleValueObject, must implement Comparable
 * @property range the validation range for the SimpleValueObject
 */
open class SimpleValueObjectValidator<P : Comparable<P>>(
   private val range: ValidationRange<P>,
) : Validator<SimpleValueObject<P>> {

   /**
    * Validates a SimpleValueObject based on the given range.
    *
    * @param t the SimpleValueObject to validate
    * @throws ValidationException if the SimpleValueObject is not valid
    */
   override fun validate(t: SimpleValueObject<P>) {
      val value = t.unwrap()

      // Range checks

      // Check if the value is less than the inclusive minimum
      range.inclusiveMin?.let { min ->
         if (value < min) throw ValidationException("Value '$value' below exclusive minimum '$min'")
      }

      // Check if the value is less than or equal to the exclusive minimum
      range.exclusiveMin?.let { min ->
         if (value <= min) throw ValidationException("Value '$value' below or at inclusive minimum '$min'")
      }

      // Check if the value is greater than the inclusive maximum
      range.inclusiveMax?.let { max ->
         if (value > max) throw ValidationException("Value '$value' above exclusive maximum '$max'")
      }

      // Check if the value is greater than or equal to the exclusive maximum
      range.exclusiveMax?.let { max ->
         if (value >= max) throw ValidationException("Value '$value' above or at inclusive maximum '$max'")
      }

   }

}
