package no.acntech.easycontainers.model

enum class Verbosity(val value: String) {
   PANIC("panic"),
   FATAL("fatal"),
   ERROR("error"),
   WARN("warn"),
   INFO("info"),
   DEBUG("debug"),
   TRACE("trace");

   companion object {
      fun of(value: String): Verbosity? {
         val valueLower = value.lowercase()
         return entries.find { it.value == valueLower }
      }
   }
}