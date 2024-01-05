package no.acntech.easycontainers

class CPU {

    companion object {
        fun from(value: String): CPU {
            return if (value.endsWith("m")) {
                CPU(value.removeSuffix("m").toDouble() / 1000)
            } else {
                CPU(value.toDouble())
            }
        }
    }

    val value: Double

    constructor(value: Double) {
        require(value >= 0) { "CPU value must be greater than or equal to 0" }
        this.value = value
    }

    override fun toString(): String {
        return if (value < 1) {
            "${(value * 1000).toInt()}m"
        } else {
            value.toString()
        }
    }
}