package no.acntech.easycontainers.util.platform

import no.acntech.easycontainers.util.text.*
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.apache.commons.exec.PumpStreamHandler
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

/**
 * Utility class for platform-specific operations.
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
         log.debug("WSL not installed ($e)")
         false
      }
   }

   /**
    * Retrieves the names of the Windows Subsystem for Linux (WSL) distributions installed on the system.
    * Returns an empty list if the current operating system is not Windows.
    *
    * @return a list of strings containing the names of the WSL distributions.
    */
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
         log.warn("Error '${e.message}' 'when trying to read WSL distributions")
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
            .joinToString(NEW_LINE)

         if (outputWithoutErrors.isNotBlank()) {

            val ips = outputWithoutErrors.splitOnWhites()

            ips.firstOrNull()?.let {
               return it.also {
                  log.debug("WSL IP address found: $it")
               }
            }
         }
      } catch (e: Exception) {
         log.error("Failed to get WSL IP address: ${e.message}", e)
      }

      return null
   }

   @Throws(IOException::class)
   fun createDirectoryInWsl(linuxPath: String, distroName: String? = getDefaultWslDistro()): String {
      val wslPath = convertLinuxPathToWindowsWslPath(linuxPath, distroName)

      // Create directory and parent directories if they do not exist
      Files.createDirectories(Path.of(wslPath)).also {
         log.trace("Directory '$linuxPath'created in WSL volume at '$wslPath' in distro '$distroName'")
      }

      return wslPath
   }

   /**
    * Converts a Windows-style path to a Linux-style path for use in WSL.
    * Example: "C:\Users\path" -> "/mnt/c/Users/path"
    */
   fun convertWindowsPathToLinuxWslPath(windowsPath: String, distroName: String? = getDefaultWslDistro()): String {
      require(isWindows()) { "WSL is a Windows OS technology only" }
      require(isWslInstalled()) { "WSL is not installed" }
      require(windowsPath.isNotBlank()) { "Windows path cannot be blank" }
      // Require that distroName is an element in the list of WSL distros
      require(distroName == null || getWslDistroNames().contains(distroName)) {
         "Distro name '$distroName' is not a valid WSL distro"
      }

      // If starts with \\wsl$\, then it's already a WSL path, and we need convert it to a Linux-style path
      return if (windowsPath.startsWith("\\\\wsl\$")) {
         val wslPath = windowsPath.replace(BACK_SLASH, FORWARD_SLASH)
         val pathDistroName = wslPath.split(FORWARD_SLASH)[3]
         wslPath.replaceFirst("\\\\wsl$\\\\$pathDistroName", EMPTY_STRING)

      } else {
         // Convert Windows-style path to WSL share path (e.g. /mnt/c/Users/path)
         val driveLetter = windowsPath.substring(0, 1).lowercase() // Extract the drive letter and convert to lowercase
         val windowsPathWithoutDrive = windowsPath.substring(2)
            .replace(BACK_SLASH, FORWARD_SLASH) // Remove the drive letter and replace backslashes with forward slashes
         "/mnt/${driveLetter}/$windowsPathWithoutDrive"
      }
   }

   /**
    * Converts a Linux-style path to a Windows-style path for use in WSL.
    * Example: "/home/johndoe/myfile.txt" -> "\\wsl$\Ubuntu\home\johndoe\myfile.txt"
    */
   fun convertLinuxPathToWindowsWslPath(linuxPath: String, distroName: String? = getDefaultWslDistro()): String {
      require(isWindows()) { "WSL is a Windows OS technology only" }
      require(isWslInstalled()) { "WSL is not installed" }
      require(linuxPath.isNotBlank()) { "Linux path cannot be blank" }
      // Require that distroName is an element in the list of WSL distros
      require(distroName == null || getWslDistroNames().contains(distroName)) {
         "Distro name '$distroName' is not a valid WSL distro"
      }

      // Convert Linux-style path to WSL share path (e.g. \\wsl$\Ubuntu\home\path)
      return Paths.get("\\\\wsl\$\\${distroName ?: getDefaultWslDistro()}$linuxPath").absolutePathString()
   }

   /**
    * Converts a Path to a string that can be used in a Docker command.
    * Example: "C:\Users\path" -> "/mnt/c/Users/path" (WSL) or "C:/Users/path" (Docker Desktop)
    */
   fun convertToDockerPath(path: Path): String {
      val convertedPath: String

      if (isWindows()) {
         // Convert Path to a Windows-style path
         val absolutePath = path.toAbsolutePath().toString()
         val driveLetter = absolutePath.substring(0, 1)

         if (isDockerDesktopOnWindows()) {
            // For Windows with Docker Desktop installed, return the Windows path in Docker format
            convertedPath = absolutePath
               .replace(BACK_SLASH, FORWARD_SLASH)
               .replaceFirst("${driveLetter}:/", "/${driveLetter}/").also {
                  log.debug("Converted path '$path' to Docker Desktop format: $it")
               }

         } else if (isWslInstalled()) {
            // For Windows with WSL installed, convert the path to WSL format
            val windowsPath = path.toString().replace(BACK_SLASH, FORWARD_SLASH)
            val wslPath = windowsPath.replaceFirst("$driveLetter:/", "/mnt/${driveLetter.lowercase()}/")
            convertedPath = wslPath.also {
               log.debug("Converted path '$path' to WSL format: $it")
            }

         } else {
            // Default case for Windows when Docker Desktop or WSL are not installed
            convertedPath = absolutePath.also {
               log.debug("Converted path '$path' to 'as is' format: $it")
            }
         }

      } else {
         // Default case, return the path as-is for non-Windows OS
         convertedPath = path.toAbsolutePath().toString().also {
            log.debug("Converted path '$path' to 'as is' format: $it")
         }
      }

      return convertedPath
   }

   fun convertWindowsPathToUnix(windowsPath: String): String {
      return windowsPath.replace(BACK_SLASH, FORWARD_SLASH).replace("^[a-zA-Z]:".toRegex(), EMPTY_STRING)
   }

   private fun stripNullBytes(input: String): String {
      return input.replace("\u0000", EMPTY_STRING)
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