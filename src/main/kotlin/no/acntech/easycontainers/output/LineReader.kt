package no.acntech.easycontainers.output

import no.acntech.easycontainers.util.io.closeQuietly
import no.acntech.easycontainers.util.lang.guardedExecution
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `LineReader` class is responsible for reading lines from an input stream and invoking an `OutputLineCallback`
 * for each line of output. Ideally, it should be used in a separate thread to avoid blocking the main thread.
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
            try {
               callback.onLine(line)
            } catch(e: IOException) {
               if(!continueFlag.get()) { // If the continueFlag is set to false, we should break the loop silently
                  callback.onLine("<Stream closed>")
                  break
               } else {
                  throw e
               }
            }
         }

         if (Thread.currentThread().isInterrupted) {
            callback.onLine("<Thread interrupted: '${Thread.currentThread().name}'>")
            Thread.currentThread().interrupt()
         } else {
            // The stream is exhausted or closed.
            callback.onLine(null)
         }
      }
   }

   /**
    * Stops the execution of the LineReader by setting the continueFlag to false and closing the input stream.
    */
   fun stop() {
      continueFlag.set(false)
      input.closeQuietly() // Close the input stream to unblock the reading thread
   }

}