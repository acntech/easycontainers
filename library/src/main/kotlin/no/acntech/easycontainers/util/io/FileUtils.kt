package no.acntech.easycontainers.util.io

import no.acntech.easycontainers.util.text.EMPTY_STRING
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.utils.IOUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The `FileUtils` class provides utility functions related to file operations.
 */
object FileUtils {

   private const val TAR_EXTENSION = ".tar"

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

   /**
    * Creates a TAR file containing the specified file and entry name.
    *
    * @param filePath The file to be included in the TAR file.
    * @param entryName The name of the entry (file) inside the TAR file.
    * @return The TAR file as a [File] object.
    */
   fun tarFile(filePath: Path, entryName: String): File {
      return Files.createTempFile(EMPTY_STRING, TAR_EXTENSION).toFile().apply {
         TarArchiveOutputStream(FileOutputStream(this)).use { tarOs ->
            tarOs.putArchiveEntry(TarArchiveEntry(filePath.toFile(), entryName))
            Files.copy(filePath, tarOs)
            tarOs.closeArchiveEntry()
         }
      }
   }

   /**
    * Creates a TAR file containing the specified directory.
    *
    * @param directoryPath The directory to be included in the TAR file.
    * @return The TAR file as a [File] object.
    */
   fun tarDir(directoryPath: Path): File {
      return Files.createTempFile(EMPTY_STRING, TAR_EXTENSION).toFile().apply {
         TarArchiveOutputStream(FileOutputStream(this)).use { tarOs ->
            Files.walk(directoryPath).use { paths ->
               paths.forEach { path ->
                  val name = directoryPath.relativize(path).toString()
                  val entry = TarArchiveEntry(path.toFile(), name)
                  if (Files.isDirectory(path)) {
                     entry.size = 0
                     entry.mode = TarArchiveEntry.DEFAULT_DIR_MODE
                  }
                  tarOs.putArchiveEntry(entry)
                  if (Files.isRegularFile(path)) {
                     Files.newInputStream(path).use { inputStream ->
                        IOUtils.copy(inputStream, tarOs)
                     }
                  }
                  tarOs.closeArchiveEntry()
               }
            }
         }
      }
   }

   /**
    * Untars a single file from the specified TAR file to the specified destination. If the destination is a directory, the
    * file will be extracted to the directory with the same name as the TAR entry. If the destination is a file, the file
    * will be extracted to the destination file.
    *
    * @param tarFile The TAR file to extract.
    * @param destination The destination to extract the TAR file to.
    */
   fun untarFile(tarFile: File, destination: Path) {
      TarArchiveInputStream(FileInputStream(tarFile)).use { tis ->
         val entry = tis.nextEntry
         if (entry != null && !entry.isDirectory) {
            val destFile = if (Files.isDirectory(destination)) {
               destination.resolve(entry.name)
            } else {
               destination
            }
            Files.copy(tis, destFile, StandardCopyOption.REPLACE_EXISTING)
         }
      }
   }

   fun untarDir(tarFile: File, destination: Path) {
      TarArchiveInputStream(FileInputStream(tarFile)).use { tis ->
         var entry = tis.nextEntry
         while (entry != null) {
            val destFile = destination.resolve(entry.name)
            if (entry.isDirectory) {
               Files.createDirectories(destFile)
            } else {
               Files.createDirectories(destFile.parent)
               Files.copy(tis, destFile, StandardCopyOption.REPLACE_EXISTING)
            }
            entry = tis.nextEntry
         }
      }
   }

}