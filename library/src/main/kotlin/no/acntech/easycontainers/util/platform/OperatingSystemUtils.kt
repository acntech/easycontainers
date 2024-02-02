package no.acntech.easycontainers.util.platform

import no.acntech.easycontainers.util.text.EMPTY_STRING
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths

object OperatingSystemUtils {

   private const val DEFAULT_DISTRO_INDICATOR = "(Default)"

   private const val WSL_INDICATOR = "Windows Subsystem for Linux Distributions"

   private val log = LoggerFactory.getLogger(OperatingSystemUtils::class.java)

   fun isLinux(): Boolean {
      return System.getProperty("os.name").lowercase().contains("linux")
   }

   fun isWindows(): Boolean {
      return System.getProperty("os.name").lowercase().contains("windows")
   }

   fun isMac(): Boolean {
      return System.getProperty("os.name").lowercase().contains("mac")
   }

   fun getWSLDistroNames(): List<String> {
      if (!isWindows()) {
         throw IllegalStateException("WSL distros can only be listed on a Windows OS")
      }

      var filteredStream: InputStream? = null

      return try {
         val processBuilder = ProcessBuilder("cmd", "/c", "chcp 65001 && wsl --list")
         val process = processBuilder.start()
         val inputStream = process.inputStream

         // Remove null bytes from the input and convert to UTF-8
         filteredStream = convertInputStreamToUTF8(inputStream)

         BufferedReader(InputStreamReader(filteredStream)).use { reader ->
            reader.lineSequence()
               .filter { it.isNotBlank() && !it.startsWith(WSL_INDICATOR) }
               .toList().also {
                  log.debug("Found WSL distros: $it")
               }
         }
      } catch (e: IOException) {
         log.debug("Error when trying to read WSL distributions", e)
         emptyList()
      } finally {
         filteredStream?.close()
      }
   }

   fun getDefaultWSLDistro(): String? {
      return (getWSLDistroNames()
         .firstOrNull { it.endsWith(DEFAULT_DISTRO_INDICATOR) }
         ?.replace(DEFAULT_DISTRO_INDICATOR, EMPTY_STRING)
         ?.trim()).also {
            if (it == null) {
               log.warn("No default WSL distro found")
            } else {
               log.debug("Default WSL distro found: $it")
            }
         }
   }

   fun createDirectoryInWSL(linuxPath: String, distroName: String? = getDefaultWSLDistro()): String? {
      val windowsPath = Paths.get("\\\\wsl$", distroName, linuxPath.replace("/", "\\"))
      return try {
         Files.createDirectories(windowsPath) // Will not fail if already exists
         log.debug("Directory created in WSL volume at: $windowsPath")
         windowsPath.toString()
      } catch (e: Exception) {
         log.error("Failed to create directory in WSL volume at: $windowsPath", e)
         null
      }
   }

   @Throws(IOException::class)
   private fun convertInputStreamToUTF8(inputStream: InputStream): InputStream {
      val outputStream = ByteArrayOutputStream()
      val buf = ByteArray(1024)
      var length: Int

      // While there are still bytes to read from the input stream
      while (inputStream.read(buf).also { length = it } != -1) {
         for (i in 0 until length) {
            // Filter out null bytes
            if (buf[i] != 0.toByte()) {
               outputStream.write(buf[i].toInt())
            }
         }
      }

      // Convert the cleaned output byte stream to an input byte stream with UTF-8 encoding
      return ByteArrayInputStream(outputStream.toByteArray())
   }

}