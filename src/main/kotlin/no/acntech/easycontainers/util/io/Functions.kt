package no.acntech.easycontainers.util.io

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

fun Closeable?.closeQuietly() {
   try {
      this?.close()
   } catch (e: IOException) {
      // Ignore
   }
}

fun ByteArrayOutputStream.toUtf8String(): String {
   return this.toString(Charsets.UTF_8)
}