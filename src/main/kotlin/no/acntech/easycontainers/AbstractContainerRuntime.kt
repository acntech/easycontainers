package no.acntech.easycontainers

import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.lang.guardedExecution
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
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
         terminateFuture = SCHEDULER.schedule(TerminateTask(), it.toSeconds(), TimeUnit.SECONDS)
      }
   }

   override fun stop() {
      terminateFuture?.cancel(false)
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

   internal abstract fun putFile(localPath: Path, remoteDir: UnixDir, remoteFilename: String?)

   internal abstract fun getFile(remoteDir: UnixDir, remoteFilename: String, localPath: Path?): Path

   internal abstract fun putDirectory(localPath: Path, remoteDir: UnixDir)

   internal abstract fun getDirectory(remoteDir: UnixDir, localPath: Path)

   internal abstract fun getDuration(): Duration?

   internal abstract fun getExitCode(): Int?

   internal abstract fun getHost(): Host?

   internal abstract fun getIpAddress(): InetAddress?
}