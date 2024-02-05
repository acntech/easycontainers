package no.acntech.easycontainers.model

/**
 * Value object representing a list of kubernetes/docker command arguments.
 */
data class Args(val args: List<Arg>) {

   companion object {

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

