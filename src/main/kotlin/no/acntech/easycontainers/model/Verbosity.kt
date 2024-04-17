package no.acntech.easycontainers.model

/**
 * Enum class representing different levels of verbosity.
 *
 * Each verbosity level has a corresponding string value.
 * The levels are: PANIC, FATAL, ERROR, WARN, INFO, DEBUG, TRACE.
 *
 * Usage:
 * To get the verbosity level from a string value, use `of(value: String)` function.
 *
 * Example:
 * val level = Verbosity.of("info")
 */
enum class Verbosity(val value: String) {
   PANIC("panic"),
   FATAL("fatal"),
   ERROR("error"),
   WARN("warn"),
   INFO("info"),
   DEBUG("debug"),
   TRACE("trace");

   companion object {
      @OptIn(ExperimentalStdlibApi::class)
      fun of(value: String): Verbosity? {
         val valueLower = value.lowercase()
         return entries.find { it.value == valueLower }
      }
   }
}