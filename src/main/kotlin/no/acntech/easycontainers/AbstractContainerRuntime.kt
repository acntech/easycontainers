package no.acntech.easycontainers

import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.lang.guardedExecution
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*

/**
 * AbstractContainerRuntime is an abstract class that provides common functionality for implementing a ContainerRuntime.
 * It contains methods for starting and stopping containers, executing commands inside containers, transferring files to and from containers,
 * and retrieving information about the container runtime.
 *
 * @property container The GenericContainer instance representing the container to manage.
 */
abstract class AbstractContainerRuntime(
   protected val container: GenericContainer,
) : ContainerRuntime {

   private inner class TerminateTask : Runnable {

      override fun run() {
         log.info("Terminating container '${container.getName()}'")

         guardedExecution(
            { kill() },
            onError = { log.warn("Error '${it.message}' killing container '${container.getName()}'", it) }
         )

         guardedExecution(
            { delete() },
            onError = { log.warn("Error '${it.message}' deleting container '${container.getName()}'", it) }
         )
      }
   }

   companion object {

      @JvmStatic
      val GENERAL_EXECUTOR_SERVICE: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

      @JvmStatic
      val SCHEDULER: ScheduledExecutorService = Executors.newScheduledThreadPool(
         1,
         Thread.ofVirtual().factory()
      )

   }

   protected val log: Logger = LoggerFactory.getLogger(javaClass)

   private var terminateFuture: Future<*>? = null

   init {
      val shutdownHook = Thread {

         // Early return: if the container has already been deleted, we don't need to do anything
         if (container.getState() == ContainerState.DELETED) {
            return@Thread
         }

         log.info("ShutdownHook: stopping and removing container '${container.getName()}'")

         guardedExecution(
            { stop() },
            listOf(Exception::class),
            { stopError ->
               log.warn("ShutdownHook: Error '${stopError.message}' stopping container '${container.getName()}'", stopError)
               guardedExecution(
                  { kill() }, // Kill the container if stopping it fails
                  listOf(Exception::class),
                  { killError ->
                     log.warn("ShutdownHook: Error '${killError.message}' killing container '${container.getName()}'", killError)
                  }
               )
            }
         )

         guardedExecution(
            { delete(true) },
            listOf(Exception::class),
            { log.warn("ShutdownHook: Error '${it.message}' deleting container '${container.getName()}'", it) }
         )
      }

      // Only add the shutdown-hook if the container is ephemeral
      if (container.isEphemeral()) {
         Runtime.getRuntime().addShutdownHook(shutdownHook)
      }
   }

   override fun getContainer(): Container {
      return container
   }

   override fun start() {
      container.builder.maxLifeTime?.let {
         terminateFuture = SCHEDULER.schedule(TerminateTask(), it.toSeconds(), TimeUnit.SECONDS).also {
            log.info("Container '${container.getName()}' will be terminated in ${it.getDelay(TimeUnit.SECONDS)} seconds")
         }
      }
   }

   override fun stop() {
      terminateFuture?.cancel(false)
      container.changeState(ContainerState.STOPPED)
   }

   internal abstract fun execute(
      executable: Executable,
      args: Args?,
      useTty: Boolean,
      workingDir: UnixDir?,
      input: InputStream?,
      output: OutputStream = OutputStream.nullOutputStream(),
      waitTimeValue: Long?,
      waitTimeUnit: TimeUnit?,
   ): Pair<Int?, String?> // Pair of exit code and stderr

   /**
    * Puts a file from local path to a remote directory on a Unix system.
    *
    * @param localFile The local path of the file to be put.
    * @param remoteDir The remote directory on the Unix system where the file will be put.
    * @param remoteFilename The name of the file on the remote directory. If null, the same name as the local file will be used.
    * @return The size of the file in bytes.
    */
   internal abstract fun putFile(localFile: Path, remoteDir: UnixDir, remoteFilename: String?): Long

   /**
    * Retrieves a file from the specified remote directory and returns the local path where the file is stored.
    *
    * @param remoteDir The remote directory from which to retrieve the file.
    * @param remoteFilename The name of the file to retrieve.
    * @param localPath The local path where the retrieved file will be stored. If null, a temporary directory will be used.
    * @return The local path where the retrieved file is stored.
    */
   internal abstract fun getFile(remoteDir: UnixDir, remoteFilename: String, localPath: Path?): Path

   /**
    * Copies a local directory to the remote directory.
    *
    * @param localDir The local directory to be copied.
    * @param remoteDir The remote directory to copy to.
    * @return The size of the directory in bytes.
    */
   internal abstract fun putDirectory(localDir: Path, remoteDir: UnixDir): Long

   /**
    * Retrieves the directory from the remote Unix system and saves it to the local file system.
    *
    * @param remoteDir The remote directory path to retrieve.
    * @param localDir The local path where the directory should be saved. Defaults to a temporary directory.
    * @return A pair containing the local path where the directory was saved and a list of paths for each file in the directory.
    */
   internal abstract fun getDirectory(
      remoteDir: UnixDir,
      localDir: Path = Files.createTempDirectory("container-download-tar").toAbsolutePath(),
   ): Pair<Path, List<Path>>

   /**
    * Retrieves the duration of the container's execution.
    *
    * @return the duration as a Duration object, or null if the duration is not available
    */
   internal abstract fun getDuration(): Duration?

   /**
    * Retrieves the exit code of the container.
    *
    * @return the exit code of the container, or null if the container has not yet exited
    */
   internal abstract fun getExitCode(): Int?

   /**
    * Retrieves the host associated with the container runtime.
    *
    * @return the host as a Host object or null if the host is not available
    */
   internal abstract fun getHost(): Host?

   /**
    * Retrieves the IP address of the container.
    *
    * @return the IP address of the container as an InetAddress object, or null if the IP address is not available
    */
   internal abstract fun getIpAddress(): InetAddress?

//   internal abstract fun waitForCompletion(timeout: Long = 0, unit: TimeUnit = TimeUnit.SECONDS): Boolean
}