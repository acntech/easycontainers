package no.acntech.easycontainers.util.platform

import no.acntech.easycontainers.util.text.EMPTY_STRING
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
         val process = ProcessBuilder("wsl", "--help").start()
         val exitCode = process.waitFor()
         exitCode == 0
      } catch (e: IOException) {
         log.debug("WSL not installed", e)
         false
      }
   }

   /**
    * Retrieves the names of all Windows Subsystem for Linux (WSL) distributions installed on the system.
    *
    * @return A list of strings representing the names of the WSL distributions.
    *         If the operating system is not Windows, an empty list is returned.
    *         If an error occurs while retrieving the distribution names, an empty list is returned.
    */
   fun getWslDistroNames(): List<String> {
      if (!isWindows()) {
         return emptyList()
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

   /**
    * Retrieves the default WSL (Windows Subsystem for Linux) distribution name.
    *
    * @return the default WSL distribution name, or null if the current operating system is not Windows
    */
   fun getDefaultWslDistro(): String? {
      if(!isWindows()) {
         return null
      }

      return (getWslDistroNames()
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

   /**
    * Creates a directory in the WSL (Windows Subsystem for Linux) volume.
    *
    * @param linuxPath The path to the directory in Linux format (e.g.*/
   fun createDirectoryInWsl(linuxPath: String, distroName: String? = getDefaultWslDistro()): String? {
      require(isWindows()) { "WSL is a Windows OS technology only" }

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

   /**
    * Checks if Docker Desktop is running on a Windows operating system.
    *
    * @return true if Docker Desktop is running on Windows, false otherwise
    */
   fun isDockerDesktopOnWindows(): Boolean {
      if (!isWindows()) {
         return false
      }

      return try {
         // Execute "docker --version" command
         val process = ProcessBuilder("cmd", "/c", "docker --version").start()

         // Wait for the process to exit and check the exit value
         val exitCode = process.waitFor()

         // Read the command output
         val output = process.inputStream.bufferedReader().readText().trim()

         // Check exit code and if output indicates Docker Desktop on Windows
         exitCode == 0 && output.contains("Docker Desktop")
      } catch (e: Exception) {
         // Exception caught - Docker might not be installed, or the command failed to execute
         false
      }
   }

   /**
    * Retrieves the IP address of the Windows Subsystem for Linux (WSL).
    *
    * @return The IP address of the WSL instance as an InetAddress, or null if not found or an error occurs.
    */
   fun getWslIpAddress(): InetAddress? {
      if (!isWindows() || !isWslInstalled()) {
         return null
      }

      try {
         // Run the command and capture the output
         val process = ProcessBuilder("wsl", "hostname", "-I").start()
         val output = process.inputStream.bufferedReader().readText().trim()

         // Wait for the process to exit and check the exit value
         val exitCode = process.waitFor()
         if (exitCode == 0 && output.isNotEmpty()) {
            // Split the output by spaces to get individual IP addresses
            val ips = output.split("\\s+".toRegex())
            // Attempt to parse the first IP address and return it as InetAddress
            ips.firstOrNull()?.let {
               return InetAddress.getByName(it)
            }
         }
      } catch (e: Exception) {
         log.error("Failed to get WSL IP address: ${e.message}", e)
      }

      // Return null if no valid IP address was found or an error occurred
      return null
   }

   /**
    * Converts the given {@link Path} to a Docker-compatible path depending on the operating system.
    *
    * @param path The path to be converted.
    * @return The converted Docker-compatible path.
    */
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

      // For Windows with WSL installed, convert the path to WSL format
      if (isWindows() && isWslInstalled()) {
         val driveLetter = path.root.toString().replace("\\", "").toLowerCase()
         val wslPath = path.toString().replace("\\", "/").replaceFirst("$driveLetter:/", "/mnt/$driveLetter/")
         return wslPath
      }

      // Default case, return the path as-is (This might be a non-Windows path or an unhandled case)
      return path.toString()
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