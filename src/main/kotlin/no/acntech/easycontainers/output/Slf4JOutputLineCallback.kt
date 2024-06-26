package no.acntech.easycontainers.output

import no.acntech.easycontainers.util.text.EMPTY_STRING
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level

/**
 * OutputLineCallback implementation that logs output lines to a SLF4J logger.
 *
 * @param logger the SLF4J logger to use
 * @param level the log level to use
 * @param marker the marker to use
 * @param prefix the prefix to use for each log line
 */
class Slf4JOutputLineCallback(
   private val logger: Logger,
   private val level: Level = Level.INFO,
   private val marker: Marker? = null,
   private val prefix: String = EMPTY_STRING,
) : OutputLineCallback {

   override fun onLine(line: String?) {
      val logLine = prefix + (line ?: "<EOF>")

      when (level) {

         Level.TRACE -> {
            if (marker != null) {
               logger.trace(marker, logLine)
            } else {
               logger.trace(logLine)
            }
         }

         Level.DEBUG -> {
            if (marker != null) {
               logger.debug(marker, logLine)
            } else {
               logger.debug(logLine)
            }
         }

         Level.INFO -> {
            if (marker != null) {
               logger.info(marker, logLine)
            } else {
               logger.info(logLine)
            }
         }

         Level.WARN -> {
            if (marker != null) {
               logger.warn(marker, logLine)
            } else {
               logger.warn(logLine)
            }
         }

         Level.ERROR -> {
            if (marker != null) {
               logger.error(marker, logLine)
            } else {
               logger.error(logLine)
            }
         }

      }
   }
}