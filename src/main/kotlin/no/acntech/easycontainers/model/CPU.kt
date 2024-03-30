package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.SimpleValueObjectValidator
import no.acntech.easycontainers.model.base.ValidationRange

/**
 * Value object representing a kubernetes/docker cpu request or limit.
 */
@JvmInline
value class CPU(val value: Double) : SimpleValueObject<Double> {

   companion object {
      fun of(value: String): CPU {
         val numericValue = if (value.endsWith("m")) {
            value.removeSuffix("m").toDoubleOrNull()?.div(1000)
         } else {
            value.toDoubleOrNull()
         }

         requireNotNull(numericValue) { "Invalid CPU format: $value" }

         return CPU(numericValue)
      }

      fun of(value: Double): CPU = CPU(value)

      private val VALIDATOR = SimpleValueObjectValidator(
         range = ValidationRange(
            inclusiveMin = 0.001,
            exclusiveMax = 64.0
         )
      )
   }

   init {
      VALIDATOR.validate(this)
   }

   override fun unwrap(): Double {
      return value
   }

   override fun toString(): String {
      return if (value < 1) {
         "${(value * 1000).toInt()}m"
      } else {
         value.toString()
      }
   }

}