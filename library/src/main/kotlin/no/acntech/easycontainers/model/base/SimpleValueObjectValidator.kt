package no.acntech.easycontainers.model.base

import no.acntech.easycontainers.util.lang.ValidationException
import no.acntech.easycontainers.util.lang.Validator

/**
 * Class for checking that a value object is within a valid range.
 */
open class SimpleValueObjectValidator<P : Comparable<P>>(
   private val range: ValidationRange<P>,
) : Validator<SimpleValueObject<P>> {

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
