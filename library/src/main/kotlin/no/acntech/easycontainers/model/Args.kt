package no.acntech.easycontainers.model

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

