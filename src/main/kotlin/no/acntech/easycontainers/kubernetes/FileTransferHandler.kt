package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.util.io.FileUtils
import no.acntech.easycontainers.util.io.pipe
import no.acntech.easycontainers.util.text.EMPTY_STRING
import no.acntech.easycontainers.util.text.FORWARD_SLASH
import org.apache.commons.compress.utils.CountingInputStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class FileTransferHandler(
   private val client: KubernetesClient,
   private val pod: Pod,
   private val container: Container,
) {

   companion object {
      private val log: Logger = LoggerFactory.getLogger(ExecHandler::class.java)
   }

   fun getFile(remoteDir: String, remoteFilename: String, localFile: Path?): Path {
      val remotePath = "${remoteDir}/$remoteFilename"
      val targetPath = localFile ?: Files.createTempFile("k8s-file-transfer-", ".tmp")

      val command = listOf("cat", remotePath)
      val output = BufferedOutputStream(FileOutputStream(targetPath.toFile()))

      val execHandler = ExecHandler(client, pod, container)

      val (exitCode, stdErr) = execHandler.execute(
         command,
         false,
         null,
         output,
         60L,
         TimeUnit.SECONDS
      )

      if ((exitCode != null && exitCode != 0) || stdErr != null) {
         val msg = "Transferring file '$localFile' to '$remotePath' failed with code $exitCode and error msg: $stdErr"
         log.error(msg)
         throw ContainerException(msg)
      }

      return targetPath
   }

   fun putFile(localFile: Path, remoteDir: String, remoteFilename: String?): Long {
      // Getting the absolute path of the remote file inside the pod
      val remotePath = "${remoteDir}/${remoteFilename ?: localFile.fileName.toString()}"

      log.debug("Transferring local file '$localFile' to remote path '$remotePath'")

      val command = listOf("sh", "-c", "cat > $remotePath")
      // Prepare the execution of the command with the file's content piped into stdin
      val input = BufferedInputStream(FileInputStream(localFile.toFile()))
      val execHandler = ExecHandler(client, pod, container)

      val (exitCode, stdErr) = execHandler.execute(
         command,
         false,
         input,
         OutputStream.nullOutputStream(),
         60L,
         TimeUnit.SECONDS
      )

      if ((exitCode != null && exitCode != 0) || stdErr != null) {
         val msg = "Transferring file '$localFile' to '$remotePath' failed with code $exitCode and error msg: $stdErr"
         log.error(msg)
         throw ContainerException(msg)
      }

      return localFile.toFile().length()
   }

   fun getDirectory(remoteDir: String, localDir: Path): Pair<Path, List<Path>> {

      // Create the local directory if it doesn't exist
      if (!Files.exists(localDir)) {
         Files.createDirectories(localDir).also {
            log.debug("Untar prep - creating directory: $localDir")
         }
      }

      // Split the remote path into parent directory and target directory
      val parentDir = remoteDir.substringBeforeLast(FORWARD_SLASH, EMPTY_STRING)
      val targetDir = remoteDir.substringAfterLast(FORWARD_SLASH)

      // Prepare the execution of the tar command to create a tar file of the directory inside the container
      // Change to the parent directory and specify the target directory as the source files
      val command = listOf("sh", "-c", "tar -cf - -C $parentDir $targetDir")
      val execHandler = ExecHandler(client, pod, container)

      // Execute the command and capture the output, which is the tar file
      val pipedInput = pipe { output ->
         val (exitCode, stdErr) = execHandler.execute(
            command,
            false,
            null,
            output,
            15L,
            TimeUnit.SECONDS
         )

         if ((exitCode != null && exitCode != 0) || stdErr != null) {
            val msg = "Transferring remote directory '$remoteDir' to local path '$localDir' " +
               "failed with code $exitCode and error msg: $stdErr"
            log.error(msg)
            throw ContainerException(msg)
         }
      }

      return FileUtils.untar(pipedInput, localDir)
   }

   fun putDirectory(localDir: Path, remoteDir: String): Long {
      log.debug("Transferring local directory '$localDir' to remote path '$remoteDir'")

      val tarInput = FileUtils.tar(localDir, true)
      val countingInput = CountingInputStream(tarInput)
      val command = listOf("sh", "-c", "tar -xf - -C $remoteDir")
      val execHandler = ExecHandler(client, pod, container)

      val (exitCode, stdErr) = execHandler.execute(
         command,
         false,
         countingInput,
         OutputStream.nullOutputStream(),
         20L,
         TimeUnit.SECONDS
      )

      if ((exitCode != null && exitCode != 0) || stdErr != null) {
         val msg = "Transferring directory '$localDir' to '$remoteDir' failed with code $exitCode and error msg: $stdErr"
         log.error(msg)
         throw ContainerException(msg)
      }

      return countingInput.bytesRead
   }


}