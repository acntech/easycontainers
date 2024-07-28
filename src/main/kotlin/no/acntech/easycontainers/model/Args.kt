package no.acntech.easycontainers.model

import no.acntech.easycontainers.util.text.splitOnWhites

/**
 * Value object representing a list of kubernetes/docker command arguments.
 */
data class Args(val args: List<Arg>) {

   companion object {

      /**
       * Creates an Args object from a whitespace separated string. Note that this wont work for arguments containing
       * spaces/whites.
       *
       * @param value The whitespace separated string to create the Args object from.
       * @return The Args object.
       */
      fun ofWhiteSpaceSeparated(value: String): Args {
         return of(value.splitOnWhites())
      }

      fun of(values: List<String>): Args {
         return of(*values.toTypedArray())
      }

      fun of(vararg values: String): Args {
         val argList = values.map { Arg.of(it) }
         return Args(argList)
      }

   }

   fun toStringList(): List<String> {
      return args.map { it.unwrap() }
   }

   override fun toString(): String {
      return args.joinToString(" ")
   }

}

