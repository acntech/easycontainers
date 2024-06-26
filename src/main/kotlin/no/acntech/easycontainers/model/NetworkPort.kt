package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.lang.RangeValidator

/**
 * Value object representing a network port - range 1-65535.
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

      private val VALIDATOR = RangeValidator(
         inclusiveMin = 1,
         inclusiveMax = 65535
      )

      val HTTP = NetworkPort(80)
      val HTTPS = NetworkPort(443)
      val SSH = NetworkPort(22)

   }

   init {
      VALIDATOR.validate(this.unwrap())
   }

   override fun unwrap(): Int {
      return value
   }

   override fun toString(): String {
      return value.toString()
   }

}