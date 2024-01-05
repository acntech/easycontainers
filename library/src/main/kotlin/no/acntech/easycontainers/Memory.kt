package no.acntech.easycontainers

import java.math.BigInteger

class Memory {

    companion object {

        val KI = BigInteger("1024") // 1024^1

        val MI = BigInteger("1048576") // 1024^2

        val GI = BigInteger("1073741824") // 1024^3

        val TI = BigInteger("1099511627776") // 1024^4

        val PI = BigInteger("1125899906842624") // 1024^5

        val EI = BigInteger("1152921504606846976") // 1024^6

        fun from(value: String): Memory {
            val trimmedValue = value.trim()
            val bytes = when {
                value.endsWith("Ki") -> BigInteger(trimmedValue.removeSuffix("Ki")) * KI
                value.endsWith("Mi") -> BigInteger(trimmedValue.removeSuffix("Mi")) * MI
                value.endsWith("Gi") -> BigInteger(trimmedValue.removeSuffix("Gi")) * GI
                value.endsWith("Ti") -> BigInteger(trimmedValue.removeSuffix("Ti")) * TI
                value.endsWith("Pi") -> BigInteger(trimmedValue.removeSuffix("Pi")) * PI
                value.endsWith("Ei") -> BigInteger(trimmedValue.removeSuffix("Ei")) * EI
                else -> BigInteger(value)
            }
            return Memory(bytes)
        }
    }

    val bytes: BigInteger

    constructor(bytes: BigInteger) {
        require(bytes > BigInteger.ZERO) { "Memory value must be greater than 0, but is $bytes" }
        this.bytes = bytes
    }

    fun toKi(): BigInteger {
        return bytes.divide(KI)
    }

    fun toMi(): BigInteger {
        return bytes.divide(MI)
    }

    fun toGi(): BigInteger {
        return bytes.divide(GI)
    }

    fun toTi(): BigInteger {
        return bytes.divide(TI)
    }

    fun toPi(): BigInteger {
        return bytes.divide(PI)
    }

    fun toEi(): BigInteger {
        return bytes.divide(EI)
    }

    override fun toString(): String {
        return when {
            bytes >= EI -> "${bytes.divide(EI)}Ei"
            bytes >= PI -> "${bytes.divide(PI)}Pi"
            bytes >= TI -> "${bytes.divide(TI)}Ti"
            bytes >= GI -> "${bytes.divide(GI)}Gi"
            bytes >= MI -> "${bytes.divide(MI)}Mi"
            bytes >= KI -> "${bytes.divide(KI)}Ki"
            else -> bytes.toString()
        }
    }

}
