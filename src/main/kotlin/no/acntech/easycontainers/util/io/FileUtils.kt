package no.acntech.easycontainers.util.io

import no.acntech.easycontainers.util.text.BACK_SLASH
import no.acntech.easycontainers.util.text.EMPTY_STRING
import no.acntech.easycontainers.util.text.FORWARD_SLASH
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The `FileUtils` class provides utility functions related to file operations.
 */
object FileUtils {

   private const val TAR_EXTENSION = ".tar"

   private val UNIX_COMPLETE_PATH_REGEX = Regex("^/([^/ ]+/)*[^/ ]+$")

   private val log = LoggerFactory.getLogger(FileUtils::class.java)

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
   @Throws(IOException::class)
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
    * Creates a TAR archive of the specified directory.
    *
    * @param dir The directory to be included in the TAR archive.
    * @param tarball The path of the TAR archive to be created.
    * @param includeParentDir Specifies whether to include the parent directory in the TAR archive. Default is false.
    * @return The path of the TAR archive.
    */
   @Throws(IOException::class)
   fun tar(
      dir: Path,
      tarball: Path = Files.createTempFile("tar-${dir.name}-", TAR_EXTENSION),
      includeParentDir: Boolean = false,
   ): Path {
      require(dir.exists() && Files.isDirectory(dir)) { "The provided path '$dir' is not a directory." }
      require(tarball.exists() && tarball.isRegularFile()) { "The provided tarball '$tarball' is not a valid file." }
      tar(dir, FileOutputStream(tarball.toFile()), includeParentDir)
      return tarball
   }

   @Throws(IOException::class)
   fun tar(dir: Path, output: OutputStream, includeParentDir: Boolean = false): Long {
      require(dir.exists() && Files.isDirectory(dir)) { "The provided path '$dir' is not a directory." }
      val bufferedOut = if (output is BufferedOutputStream) output else BufferedOutputStream(output)
      val actualDir = if (includeParentDir) dir.parent else dir
      var totalBytes = 0L
      TarArchiveOutputStream(bufferedOut).use { tarOutput ->
         Files.walk(dir).use { paths ->
            paths.forEach { path ->
               val name = actualDir.relativize(path).toString().replace(BACK_SLASH, FORWARD_SLASH).removePrefix(FORWARD_SLASH)
               val entry = prepareTarEntry(path, name)
               totalBytes += writeToTarOutput(tarOutput, path, entry)
            }
         }
      }
      return totalBytes
   }

   private fun prepareTarEntry(path: Path, name: String): TarArchiveEntry {
      val entry = TarArchiveEntry(path.toFile(), name).also {
         log.trace("Adding tar entry: $name -> ${path.toAbsolutePath()} (${path.toFile().length()} bytes)")
      }
      if (Files.isDirectory(path)) {
         entry.size = 0
         entry.mode = TarArchiveEntry.DEFAULT_DIR_MODE
      }
      return entry
   }

   private fun writeToTarOutput(tarOutput: TarArchiveOutputStream, path: Path, entry: TarArchiveEntry): Long {
      var totalBytesForEntry = 0L
      tarOutput.putArchiveEntry(entry)
      if (Files.isRegularFile(path)) {
         Files.newInputStream(path).use { input ->
            totalBytesForEntry += input.transferTo(tarOutput)
         }
      }
      tarOutput.closeArchiveEntry()
      return totalBytesForEntry
   }

   /**
    * Creates an input stream containing a TAR archive of the specified directory. Note that the piping to the input is done
    * in a separate (virtual) thread.
    *
    * @param dir The directory to be included in the TAR archive.
    * @param includeParentDir Specifies whether to include the parent directory in the TAR archive. Default is false.
    * @return An input stream containing the TAR archive.
    */
   @Throws(IOException::class)
   fun tar(dir: Path, includeParentDir: Boolean = false): InputStream {
      require(dir.exists() && Files.isDirectory(dir)) { "The provided path '$dir' is not a directory." }
      return pipe { output -> tar(dir, output, includeParentDir) }
   }

   /**
    * Untars a single file from the specified TAR file to the specified destination. If the destination is a directory, the
    * file will be extracted to the directory with the same name as the TAR entry. If the destination is a file, the file
    * will be extracted to the destination file.
    *
    * @param tarFile The TAR file to extract.
    * @param destination The destination to extract the TAR file to - either a directory or a file.
    */
   @Throws(IOException::class)
   fun untarFile(tarFile: File, destination: Path = Files.createTempDirectory("untar-").toAbsolutePath()): Path {
      require(tarFile.exists() && tarFile.isFile) { "The provided file '$tarFile' is not a valid file." }
//      require(Files.isDirectory(destination) || (!Files.exists(destination) && destination.parent.toFile().canWrite())) {
//         "The provided destination '$destination' must be an existing directory or a non-existing file that can be written to."
//      }

      TarArchiveInputStream(BufferedInputStream(FileInputStream(tarFile))).use { tis ->
         val entry = tis.nextEntry
         if (entry != null && !entry.isDirectory) {
            val destFile = if (Files.isDirectory(destination)) {
               destination.resolve(entry.name)
            } else {
               destination
            }
            Files.copy(tis, destFile, StandardCopyOption.REPLACE_EXISTING).also {
               log.trace("Untaring - creating file: $destFile")
            }
         }
      }
      return destination
   }

   /**
    * Extracts the contents of a tarball to a specified destination directory.
    *
    * @param tarball The tarball file to be extracted.
    * @param destination The destination directory where the contents should be extracted to.
    *                    If not provided, a temporary directory will be created.
    * @return A Pair containing the path of the destination directory and a list of extracted files.
    * @throws IllegalArgumentException if the destination path is not a directory.
    */
   @Throws(IOException::class)
   fun untar(
      tarball: File,
      destination: Path = Files.createTempDirectory("untar-${tarball.name}-").toAbsolutePath(),
   ): Pair<Path, List<Path>> {
      require(tarball.exists() && tarball.isFile) { "The provided file '$tarball' is not a valid file." }
      require(destination.exists() && Files.isDirectory(destination)) { "The provided path '$destination' is not a directory." }
      return untar(FileInputStream(tarball), destination)
   }

   /**
    * Extracts the contents of a tarball from the given input stream to the specified destination directory.
    *
    * @param input The input stream of the tarball to be extracted.
    * @param destination The destination directory where the contents should be extracted to.
    *                    If not provided, a temporary directory will be created.
    * @return A Pair containing the path of the destination directory and a list of extracted files.
    * @throws IllegalArgumentException if the destination path is not a directory.
    */
   @Throws(IOException::class)
   fun untar(
      input: InputStream,
      destination: Path = Files.createTempDirectory("untar-").toAbsolutePath(),
   ): Pair<Path, List<Path>> {
      require(destination.exists() && Files.isDirectory(destination)) {
         "The provided destination '$destination' is not a directory."
      }

      log.trace("Untaring input to: $destination")

      val bufferedInput = if (input is BufferedInputStream) input else BufferedInputStream(input)
      val files: MutableList<Path> = mutableListOf()
      processArchiveEntries(bufferedInput, destination, files)
      return Pair(destination, files.toList())
   }

   private fun processArchiveEntries(bufferedInput: BufferedInputStream, destination: Path, files: MutableList<Path>) {
      TarArchiveInputStream(bufferedInput).use { tarInput ->
         var entry = tarInput.nextEntry
         while (entry != null) {
            processEntry(entry, destination, tarInput, files)
            entry = tarInput.nextEntry
         }
      }
   }

   private fun processEntry(
      entry: ArchiveEntry,
      destination: Path,
      tarInput: TarArchiveInputStream,
      files: MutableList<Path>,
   ) {
      val destFile = destination.resolve(entry.name)
      if (entry.isDirectory) {
         Files.createDirectories(destFile).also {
            log.trace("Untaring - creating directory: $destFile")
         }
      } else {
         Files.createDirectories(destFile.parent)
         Files.copy(
            tarInput, destFile,
            StandardCopyOption.REPLACE_EXISTING
         ).also {
            log.trace("Untaring - creating file: $destFile")
         }
         files.add(destFile)
      }
   }

}