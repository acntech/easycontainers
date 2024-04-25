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

      const val MILLI_CPU_SUFFIX = "m"

      private val VALIDATOR = SimpleValueObjectValidator(
         range = ValidationRange(
            inclusiveMin = 0.001,
            exclusiveMax = 64.0
         )
      )

      fun of(value: String): CPU {
         val numericValue = if (value.endsWith(MILLI_CPU_SUFFIX)) {
            value.removeSuffix(MILLI_CPU_SUFFIX).toDoubleOrNull()?.div(1000)
         } else {
            value.toDoubleOrNull()
         }

         requireNotNull(numericValue) { "Invalid CPU request/limit format: $value" }

         return CPU(numericValue)
      }

      fun of(value: Double): CPU = CPU(value)
   }

   init {
      VALIDATOR.validate(this)
   }

   override fun unwrap(): Double {
      return value
   }

   override fun toString(): String = if (value < 1) "${(value * 1000).toInt()}$MILLI_CPU_SUFFIX" else value.toString()

}