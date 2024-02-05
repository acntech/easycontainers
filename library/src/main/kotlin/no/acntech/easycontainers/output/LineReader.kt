package no.acntech.easycontainers.output

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `LineReader` class is responsible for reading lines from an input stream and invoking an `OutputLineCallback`
 * for each line of output.
 *
 * @property input The input stream to read from.
 * @property callback The callback function to invoke for each line of output.
 */
class LineReader(
   private val input: InputStream,
   private val callback: OutputLineCallback,
) {

   private val continueFlag: AtomicBoolean = AtomicBoolean(true)

   /**
    * Reads lines from an input stream and invokes a callback function for each line of output.
    * If the stream is exhausted, the thread is interrupted, or its explicitly stopped by calling `stop` on the reader,
    * the reading process will stop.
    */
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

   /**
    * Stops the execution of the LineReader.
    */
   fun stop() {
      continueFlag.set(false)
   }

}