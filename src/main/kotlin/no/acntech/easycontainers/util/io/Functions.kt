package no.acntech.easycontainers.util.io

import java.io.*

/**
 * Closes a Closeable object quietly, without throwing any exception.
 *
 * @receiver The Closeable object to be closed. It can be null.
 */
fun Closeable?.closeQuietly() {
   try {
      this?.close()
   } catch (e: IOException) {
      // Ignore
   }
}

/**
 * Converts the content of this ByteArrayOutputStream to a UTF-8 encoded string.
 *
 * @return the UTF-8 string representation of the content in this ByteArrayOutputStream.
 */
fun ByteArrayOutputStream.toUtf8String(): String {
   return this.toString(Charsets.UTF_8)
}

/**
 * Executes the given (write) operation in a separate (virtual) thread and returns an [InputStream] that reads
 * from the result of the operation - effectively creating a "reactive" pipe between the [OutputStream] and
 * the [InputStream]. This means that this method is non-blocking and the operation is executed asynchronously.
 *
 * @param operation The operation to be executed, which takes an [OutputStream] as input and returns a result of type T.
 * @return An [InputStream] that reads from the output of the operation.
 */
fun <T> pipe(operation: (OutputStream) -> T): InputStream {
   val pipedOut = PipedOutputStream()
   val pipedIn = BufferedInputStream(PipedInputStream(pipedOut))

   Thread.startVirtualThread {
      operation(pipedOut)
   }

   return pipedIn
}

