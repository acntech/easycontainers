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
 * Streams are buffered.
 *
 * @param operation The operation to execute in a separate thread. The operation receives an [OutputStream] as a parameter.
 *                  The default operation is a no-op.
 * @return An [InputStream] that reads from the output of the operation.
 */
@Suppress("UNCHECKED_CAST")
fun <T> pipe(
   operation: (OutputStream) -> T = { _: OutputStream -> null as T },
): InputStream {
   return pipe(operation, true)
}

/**
 * Executes the given (write) operation in a separate (virtual) thread and returns an [InputStream] that reads
 * from the result of the operation - effectively creating a "reactive" pipe between the [OutputStream] and
 * the [InputStream]. This means that this method is non-blocking and the operation is executed asynchronously.
 *
 * @param operation The operation to execute in a separate thread. The operation receives an [OutputStream] as a parameter.
 *                  The default operation is a no-op.
 * @param buffered Specifies whether the [InputStream] and [OutputStream] should be buffered. Default is true.
 * @return An [InputStream] that reads from the output of the operation.
 */
@Suppress("UNCHECKED_CAST")
fun <T> pipe(
   operation: (OutputStream) -> T = { _: OutputStream -> null as T },
   buffered: Boolean = true,
): InputStream {
   val output = PipedOutputStream()
   val input = if (buffered) BufferedInputStream(PipedInputStream(output)) else PipedInputStream(output)

   Thread.startVirtualThread {
      operation(if (buffered) BufferedOutputStream(output) else output)
   }

   return input
}

