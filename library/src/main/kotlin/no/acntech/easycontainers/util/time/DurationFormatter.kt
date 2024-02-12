package no.acntech.easycontainers.util.time

import java.time.Duration

object DurationFormatter {

   fun formatAsMinutesAndSecondsLong(duration: Duration): String {
      val minutes = duration.toMinutes()
      val seconds = duration.seconds % 60 // For compatibility with Java versions before 9
      return "$minutes minutes and $seconds seconds"
   }
}