package no.acntech.easycontainers.util.time

import java.time.Instant
import java.util.concurrent.TimeUnit

class WaitTimeCalculator(
   private var waitTimeValue: Long,
   private var waitTimeUnit: TimeUnit,
   private val startTime: Long = System.nanoTime()
) {

   fun getRemainingTime(unit: TimeUnit = waitTimeUnit): Long {
      val elapsed = System.nanoTime() - startTime
      val elapsedInWaitUnit = waitTimeUnit.convert(elapsed, TimeUnit.NANOSECONDS)
      val remaining = waitTimeValue - elapsedInWaitUnit

      return unit.convert(remaining, waitTimeUnit)
   }
}