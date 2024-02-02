package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.SimpleValueObjectValidator
import no.acntech.easycontainers.model.base.ValidationRange

/**
 * Value object representing a kubernetes/docker cpu request or limit.
 */
@JvmInline
value class NetworkPort(val value: Int) : SimpleValueObject<Int> {

   companion object {

      fun of(value: Int): NetworkPort {
         return NetworkPort(value)
      }

      fun of(value: String): NetworkPort {
         return NetworkPort(value.toInt())
      }

      private val VALIDATOR = SimpleValueObjectValidator(
         range = ValidationRange(
            inclusiveMin = 1,
            inclusiveMax = 65535
         )
      )

      val HTTP = NetworkPort(80)

      val HTTPS = NetworkPort(443)

   }

   init {
      VALIDATOR.validate(this)
   }

   override fun unwrap(): Int {
      return value
   }

   override fun toString(): String {
      return value.toString()
   }

}