package no.acntech.easycontainers.util.platform

import no.acntech.easycontainers.util.platform.PlatformUtils.isDockerDesktopOnWindows
import no.acntech.easycontainers.util.platform.PlatformUtils.isMac
import no.acntech.easycontainers.util.platform.PlatformUtils.isWslInstalled
import no.acntech.easycontainers.util.text.EMPTY_STRING
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.apache.commons.exec.PumpStreamHandler
import org.slf4j.LoggerFactory
import java.io.*
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Utility class for platform-specific operations and checks.
 */
object PlatformUtils {

   private const val DEFAULT_DISTRO_INDICATOR = "(Default)"

   private const val WSL_INDICATOR = "Windows Subsystem for Linux Distributions"

   private val log = LoggerFactory.getLogger(PlatformUtils::class.java)

   /**
    * Checks if the current operating system is Linux.
    *
    * @return true if the current operating system is Linux, false otherwise.
    */
   fun isLinux(): Boolean {
      return System.getProperty("os.name").lowercase().contains("linux")
   }

   /**
    * Checks if the current operating system is Windows.
    * @return true if the current operating system is Windows, false otherwise.
    */
   fun isWindows(): Boolean {
      return System.getProperty("os.name").lowercase().contains("windows")
   }

   /**
    * Checks if the current operating system is macOS.
    *
    * @return true if the operating system is macOS, false otherwise.
    */
   fun isMac(): Boolean {
      return System.getProperty("os.name").lowercase().contains("mac")
   }

   /**
    * Checks if Windows Subsystem for Linux (WSL) is installed on the system.
    *
    * @return `true` if WSL is installed, `false` otherwise.
    */

   fun isWslInstalled(): Boolean {
      if (!isWindows()) {
         return false
      }

      return try {
         val cmd = CommandLine.parse("wsl --version")
         val executor = DefaultExecutor.builder().get()
         executor.execute(cmd) == 0
      } catch (e: IOException) {
         log.debug("WSL not installed", e)
         false
      }
   }

   fun getWslDistroNames(): List<String> {
      if (!isWindows()) {
         return emptyList()
      }

      return try {
         val cmd = CommandLine.parse("cmd /c chcp 65001 && wsl --list")
         val executor = DefaultExecutor.builder().get()
         val outputStream = ByteArrayOutputStream()
         executor.streamHandler = PumpStreamHandler(outputStream)
         executor.execute(cmd)

         val outputWithNullBytes = outputStream.toString()
         val outputWithoutNullBytes = stripNullBytes(outputWithNullBytes)

         BufferedReader(StringReader(outputWithoutNullBytes)).use { reader ->
            reader.lineSequence()
               .filter { it.isNotBlank() && !it.startsWith(WSL_INDICATOR) && !it.contains("code page") }
               .map { it.replace("(Default)", "").trim() } // Strip "(Default)" and trim the result
               .toList().also {
                  log.debug("Found WSL distros: $it")
               }
         }
      } catch (e: IOException) {
         log.debug("Error when trying to read WSL distributions", e)
         emptyList()
      }
   }

   fun getDefaultWslDistro(): String? {
      if (!isWindows()) {
         return null
      }

      return try {
         // Run command to fetch the WSL distros list
         val cmd = CommandLine.parse("cmd /c chcp 65001 && wsl --list")
         val executor = DefaultExecutor.builder().get()
         val outputStream = ByteArrayOutputStream()
         executor.streamHandler = PumpStreamHandler(outputStream)
         executor.execute(cmd)

         val outputWithNullBytes = outputStream.toString()
         val outputWithoutNullBytes = stripNullBytes(outputWithNullBytes)
         val distroList = BufferedReader(StringReader(outputWithoutNullBytes)).readLines()

         val distroNames = distroList.filterNot { it.contains("code page") }

         // Find the distro name that ends with the DEFAULT_DISTRO_INDICATOR
         val defaultDistro = distroNames
            .firstOrNull { it.contains("(Default)") }
            ?.removeSuffix("(Default)")?.trim()  // remove "(Default)" and trim whitespace

         if (defaultDistro != null) {
            log.debug("Default WSL distro found: $defaultDistro")
         } else {
            log.warn("No default WSL distro found")
         }

         defaultDistro
      } catch (e: Exception) {
         log.error("Failed to get default WSL distro", e)
         return null
      }
   }

   fun isDockerDesktopOnWindows(): Boolean {
      if (!isWindows()) {
         return false
      }

      return try {
         val cmd = CommandLine.parse("cmd /c docker --version")
         val executor = DefaultExecutor.builder().get()
         val outputStream = ByteArrayOutputStream()
         executor.streamHandler = PumpStreamHandler(outputStream)
         val exitCode = executor.execute(cmd)
         val output = outputStream.toString().trim()
         exitCode == 0 && output.contains("Docker Desktop")
      } catch (e: Exception) {
         false
      }
   }

   fun getWslIpAddress(): String? {
      if (!isWindows() || !isWslInstalled()) {
         return null
      }

      try {
         val cmd = CommandLine.parse("wsl hostname -I")
         val executor = DefaultExecutor.builder().get()
         val outputStream = ByteArrayOutputStream()
         executor.streamHandler = PumpStreamHandler(outputStream)
         val exitCode = executor.execute(cmd)

         val output = stripNullBytes(outputStream.toString().trim())

         val outputWithoutErrors = output.lines()
            .filterNot { it.startsWith("<3>WSL") }
            .joinToString("\n")

         if (outputWithoutErrors.isNotBlank()) {

            val ips = outputWithoutErrors.split("\\s+".toRegex())

            ips.firstOrNull()?.let {
               return it
            }
         }
      } catch (e: Exception) {
         log.error("Failed to get WSL IP address: ${e.message}", e)
      }

      return null
   }

   @Throws(IOException::class)
   fun createDirectoryInWsl(linuxPath: String, distroName: String? = getDefaultWslDistro()): String? {
      require(isWindows()) { "WSL is a Windows OS technology only" }
      require(isWslInstalled()) { "WSL is not installed" }
      require(linuxPath.isNotBlank()) { "Linux path cannot be blank" }
      // Require that distroName is an element in the list of WSL distros
      require(distroName == null || getWslDistroNames().contains(distroName)) {
         "Distro name '$distroName' is not a valid WSL distro"
      }

      // Convert Linux-style path to WSL share path (e.g. \\wsl$\Ubuntu\home\path)
      val wslPath = Paths.get("\\\\wsl$\\${distroName ?: getDefaultWslDistro()}$linuxPath")

      // Create directory and parent directories if they do not exist
      Files.createDirectories(wslPath)
      log.trace("Directory created in WSL volume at: $linuxPath in distro $distroName")
      return linuxPath
   }

   fun convertToDockerPath(path: Path): String {
      // For Linux or Mac, return the absolute path
      if (isLinux() || isMac()) {
         return path.toAbsolutePath().toString()
      }

      // For Windows with Docker Desktop installed, return the Windows path in Docker format
      if (isWindows() && isDockerDesktopOnWindows()) {
         // Convert Path to a Windows-style path recognizable by Docker Desktop
         // Dynamically handle the drive letter
         val absolutePath = path.toAbsolutePath().toString()
         val driveLetter = absolutePath.substring(0, 1).lowercase() // Extract the drive letter and convert to lowercase
         return absolutePath.replace("\\", "/").replaceFirst("${driveLetter}:/", "/${driveLetter}/")
      }

      if (isWindows() && isWslInstalled()) {
         // For Windows with WSL installed, convert the path to WSL format
         val driveLetter = path.root.toString().replace("\\", "").replace(":", "")
         val windowsPath = path.toString().replace("\\", "/")
         val wslPath = windowsPath.replaceFirst("$driveLetter:/", "/mnt/${driveLetter.lowercase()}/")
         return wslPath
      }

      // Default case, return the path as-is (This might be a non-Windows path or an unhandled case)
      return path.toString()
   }

   private fun stripNullBytes(input: String): String {
      return input.replace("\u0000", "")
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