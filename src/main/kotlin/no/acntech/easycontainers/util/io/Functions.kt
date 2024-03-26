package no.acntech.easycontainers.util.io

import java.io.Closeable
import java.io.IOException

fun Closeable?.closeQuietly() {
   try {
      this?.close()
   } catch (e: IOException) {
      // Ignore
   }
}