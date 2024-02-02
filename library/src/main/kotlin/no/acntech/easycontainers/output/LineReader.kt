package no.acntech.easycontainers.output

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class LineReader(
   private val input: InputStream,
   private val callback: LineCallback,
) {

   private val continueFlag: AtomicBoolean = AtomicBoolean(true)

   fun read() {
      input.bufferedReader().use { reader ->
         var line: String?

         // Read until the stream is exhausted, the thread is interrupted, or the continueFlag is set to false
         // - whatever comes first
         while (reader.readLine().also { line = it } != null && continueFlag.get() && !Thread.currentThread().isInterrupted) {
            callback.onLine(line)
         }

         if (Thread.currentThread().isInterrupted) {
            callback.onLine("<Thread interrupted>")
            Thread.currentThread().interrupt()
         } else {
            // The stream is exhausted
            callback.onLine(null)
         }
      }
   }

   fun stop() {
      continueFlag.set(false)
   }

}