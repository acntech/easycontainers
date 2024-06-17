package no.acntech.easycontainers.model.base

/**
 * A DDD ValueObject holding a single value. The unwrap method can e.g. assist in mapping the
 * SimpleValueObject to a DTO or to a repository object.
 *
 * The SimpleValueObject would normally restrict the underlying value using domain specific rules - e.g. applying
 * range restrictions (for numbers), non-null, non-blank and length restrictions for Strings, etc.
 *
 * A SimpleValueObject is usually constructed using a single-argument constructor or (even better)
 * with a static <code>of(P p)</code> method - or both.
 */
interface SimpleValueObject<P : Comparable<P>> : ValueObject, Comparable<SimpleValueObject<P>> {

   fun unwrap(): P

   override fun compareTo(other: SimpleValueObject<P>): Int {
      return unwrap().compareTo(other.unwrap())
   }

}
