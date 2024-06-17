package test.acntech.easycontainers.util.platform

import no.acntech.easycontainers.util.platform.PlatformUtils.convertToDockerPath
import no.acntech.easycontainers.util.platform.PlatformUtils.createDirectoryInWsl
import no.acntech.easycontainers.util.platform.PlatformUtils.getWslDistroNames
import no.acntech.easycontainers.util.platform.PlatformUtils.getWslIpAddress
import no.acntech.easycontainers.util.platform.PlatformUtils.isDockerDesktopOnWindows
import no.acntech.easycontainers.util.platform.PlatformUtils.isWindows
import no.acntech.easycontainers.util.platform.PlatformUtils.isWslInstalled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.nio.file.Paths

class PlatformUtilsTest {

   private val log = LoggerFactory.getLogger(PlatformUtilsTest::class.java)

   @Test
   fun `isWindows should return true if OS is Windows`() {
      assertTrue(isWindows())
   }

   @Test
   fun `isWslInstalled should return true if WSL is installed`() {
      assertTrue(isWslInstalled())
   }

   @Test
   fun `getWslDistroNames should return list of distros if WSL is installed`() {
      assertDoesNotThrow { getWslDistroNames() }
   }

   @Test
   fun `isDockerDesktopInstalled should return true if Docker is installed`() {
      assertFalse(isDockerDesktopOnWindows())
   }

   @Test
   fun `getWslIpAddress should return non-null if WSL is installed`() {
      val wslIpAddress = getWslIpAddress().also { log.info("WSL IP address: $it") }
      assertNotNull(wslIpAddress)
   }

   @Test
   fun `createDirectoryInWsl should not throw for valid path and distro`() {
      val wslDistroName = getWslDistroNames().first()

      assertDoesNotThrow { createDirectoryInWsl("/tmp/foo", wslDistroName) }
   }

   @Test
   fun `createDirectoryInWsl should throw for invalid distro`() {
      assertThrows<IllegalArgumentException> {
         createDirectoryInWsl("/home/test", "nonExistentDistro")
      }
   }

   @Test
   fun `convertToDockerPath should convert Windows path into valid WSL format`() {

      // Path on Windows are like 'C:\Users\test'
      val winPath = Paths.get("C:\\Users\\test").also {
         log.info("Windows path: $it")
      }

      val dockerPath = convertToDockerPath(winPath).also {
         log.info("Docker path: $it")

      }

      // Docker path should look like '/mnt/c/Users/test'
      val expectedPath = "/mnt/c/Users/test"

      assertEquals(expectedPath, dockerPath)
   }
}