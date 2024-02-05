package no.acntech.easycontainers.util.io

/**
 * The `FileUtils` class provides utility functions related to file operations.
 */
object FileUtils {

   private val UNIX_COMPLETE_PATH_REGEX = Regex("^/([^/ ]+/)*[^/ ]+$")

   /**
    * Checks if the provided path is a complete Unix path.
    *
    * @param path The path to check.
    * @return Returns true if the provided path is a complete Unix path, otherwise false.
    */
   fun isCompleteUnixPath(path: String): Boolean {
      return path.matches(UNIX_COMPLETE_PATH_REGEX)
   }


}