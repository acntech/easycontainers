package no.acntech.easycontainers.util.time

import java.util.concurrent.TimeUnit

/**
 * WaitTimeCalculator is a utility class that calculates the remaining time for a given wait time value,
 * unit and start time.
 *
 * @property waitTimeValue The wait time value
 * @property waitTimeUnit The wait time unit
 * @property startTime The start time of the wait time calculation
 */
data class WaitTimeCalculator(
   val waitTimeValue: Long,
   val waitTimeUnit: TimeUnit,
   val startTime: Long = System.nanoTime(),
) {

   /**
    * Calculates the remaining time based on the unit provided, calculated from the start time for this instance.
    *
    * @param unit The time unit to return the remaining time in. Defaults to the wait time unit provided with
    *             this instance.
    * @return The remaining time in the specified time unit.
    */
   fun getRemainingTime(unit: TimeUnit = waitTimeUnit): Long {
      val elapsed = System.nanoTime() - startTime
      val elapsedInWaitUnit = waitTimeUnit.convert(elapsed, TimeUnit.NANOSECONDS)
      val remaining = waitTimeValue - elapsedInWaitUnit

      return unit.convert(remaining, waitTimeUnit)
   }
}