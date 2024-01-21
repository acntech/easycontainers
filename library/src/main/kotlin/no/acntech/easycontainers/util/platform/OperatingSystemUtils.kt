package no.acntech.easycontainers.util.platform

import no.acntech.easycontainers.util.text.EMPTY_STRING
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths

object OperatingSystemUtils {

   private const val DEFAULT_DISTRO_INDICATOR = "(Default)"

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

   @Throws(IOException::class)
   fun getWSLDistroNames(): List<String> {
      if (!isWindows()) {
         throw IllegalStateException("WSL distros can only be listed on a Windows OS")
      }

      // Try to make sure that we can get the output in UTF-8
      val processBuilder = ProcessBuilder("cmd", "/c", "chcp 65001 && wsl --list")

      val process = processBuilder.start()

      // Get rid of null bytes
      val filteredBytes = process.inputStream.readAllBytes().filter { it != 0.toByte() }

      val filteredStream = ByteArrayInputStream(filteredBytes.toByteArray())

      return BufferedReader(InputStreamReader(filteredStream, Charsets.UTF_8)).use { reader ->
         reader.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("Windows Subsystem for Linux Distributions") }
            .toList().also {
               log.debug("Found WSL distros: $it")
            }
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

}