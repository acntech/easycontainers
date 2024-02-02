package no.acntech.easycontainers.model.base

/**
 * Class representing the valid range of a value object with an underlying comparable type.
 */
data class ValidationRange<P : Comparable<P>>(
   val inclusiveMin: P? = null,
   val exclusiveMin: P? = null,
   val inclusiveMax: P? = null,
   val exclusiveMax: P? = null,
)