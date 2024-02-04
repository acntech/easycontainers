package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.model.base.SimpleValueObjectValidator
import no.acntech.easycontainers.model.base.ValidationRange
import java.math.BigInteger

/**
 * Value object representing kubernetes/docker memory.
 */
@JvmInline
value class Memory(val bytes: BigInteger) : SimpleValueObject<BigInteger> {

   companion object {

      val Ki = BigInteger("1024") // 1024^1

      val Mi = BigInteger("1048576") // 1024^2

      val Gi = BigInteger("1073741824") // 1024^3

      val Ti = BigInteger("1099511627776") // 1024^4

      val Pi = BigInteger("1125899906842624") // 1024^5

      val Ei = BigInteger("1152921504606846976") // 1024^6

      private val UNIT_MAP = mapOf(
         "Ki" to Ki, // 1024^1
         "Mi" to Mi, // 1024^2
         "Gi" to Gi, // 1024^3
         "Ti" to Ti, // 1024^4
         "Pi" to Pi, // 1024^5
         "Ei" to Ei, // 1024^6
      )

      fun of(value: String): Memory {
         val trimmedValue = value.trim()
         val (number, unit) = UNIT_MAP.entries.find { trimmedValue.endsWith(it.key) }
            ?.let { Pair(trimmedValue.removeSuffix(it.key), it.value) }
            ?: Pair(trimmedValue, BigInteger.ONE)

         val numericValue = number.toBigIntegerOrNull()
            ?: throw IllegalArgumentException("Invalid memory format: $value")

         return Memory(numericValue * unit)
      }

      private val VALIDATOR = SimpleValueObjectValidator(
         range = ValidationRange(
            exclusiveMin = BigInteger.ZERO,
            exclusiveMax = BigInteger("9223372036854775807") // 2^63 - 1
         )
      )
   }

   init {
      VALIDATOR.validate(this)
   }

   override fun unwrap(): BigInteger {
      return bytes
   }

   fun toKi(): BigInteger {
      return bytes.divide(Ki)
   }

   fun toMi(): BigInteger {
      return bytes.divide(Mi)
   }

   fun toGi(): BigInteger {
      return bytes.divide(Gi)
   }

   fun toTi(): BigInteger {
      return bytes.divide(Ti)
   }

   fun toPi(): BigInteger {
      return bytes.divide(Pi)
   }

   fun toEi(): BigInteger {
      return bytes.divide(Ei)
   }

   fun toFormattedString(): String {
      return when {
         bytes >= Ei -> "${bytes.divide(Ei)}EiB"
         bytes >= Pi -> "${bytes.divide(Pi)}PiB"
         bytes >= Ti -> "${bytes.divide(Ti)}TiB"
         bytes >= Gi -> "${bytes.divide(Gi)}GiB"
         bytes >= Mi -> "${bytes.divide(Mi)}MiB"
         bytes >= Ki -> "${bytes.divide(Ki)}KiB"
         else -> "${bytes}B"
      }
   }

   override fun toString(): String {
      return toFormattedString()
   }

}
