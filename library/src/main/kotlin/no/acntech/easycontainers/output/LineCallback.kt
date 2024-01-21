package no.acntech.easycontainers.output

/**
 * Functional interface for handling output from a container.
 */
fun interface LineCallback {

   /**
    * Callback for each line of output, new line character(s) are not included.
    *
    * @param line the line of output (excluding new line character(s)) or null if end of stream
    */
   fun onLine(line: String?)
}