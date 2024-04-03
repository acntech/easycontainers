package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.ExecListener
import io.fabric8.kubernetes.client.dsl.ExecWatch
import no.acntech.easycontainers.AbstractContainerRuntime
import no.acntech.easycontainers.ContainerException
import no.acntech.easycontainers.kubernetes.ErrorSupport.handleK8sException
import no.acntech.easycontainers.util.io.closeQuietly
import no.acntech.easycontainers.util.io.toUtf8String
import no.acntech.easycontainers.util.text.SPACE
import no.acntech.easycontainers.util.text.truncate
import no.acntech.easycontainers.util.time.WaitTimeCalculator
import org.apache.commons.io.output.TeeOutputStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class CommandSupport(
   private val client: KubernetesClient,
   private val pod: Pod,
   private val container: Container,
) {

   companion object {
      private val log: Logger = LoggerFactory.getLogger(CommandSupport::class.java)
   }

   /**
    * Executes a command in the container.
    * @return a pair containing the exit code and the error output of the command
    */
   fun execute(
      command: List<String>,
      useTty: Boolean = false,
      stdIn: InputStream?,
      stdOut: OutputStream,
      waitTimeValue: Long? = null,
      waitTimeUnit: TimeUnit? = null,
   ): Pair<Int?, String?> {
      log.debug("Executing command '${command.joinToString(SPACE)}' in pod '${pod.metadata.name}' / container '${container.name}'")

      val waitTimeCalculator = if (waitTimeValue != null && waitTimeUnit != null) {
         WaitTimeCalculator(waitTimeValue, waitTimeUnit)
      } else {
         null
      }

      val stdErr = ByteArrayOutputStream()
      val cmdExitCode = AtomicReference<Int>()
      val error = AtomicReference<Throwable>()
      val alive = AtomicBoolean(false)
      val started = CountDownLatch(1)
      val finished = CountDownLatch(1)
      var execWatch: ExecWatch? = null

      try {
         execWatch = executeCommand(
            pod,
            container,
            command,
            useTty,
            stdIn,
            true,
            stdOut,
            true,
            stdErr,
            true,
            cmdExitCode,
            error,
            alive,
            started,
            finished
         )

         val remainingTime = waitTimeCalculator?.getRemainingTime()
         waitForLatch(started, remainingTime, waitTimeUnit)

         val processFutures = createExecutionFutures(execWatch, stdIn, stdOut, stdErr)

         try {
            waitForCompletion(
               processFutures,
               stdIn != null,
               finished,
               waitTimeCalculator,
               waitTimeUnit
            )
         } catch (e: TimeoutException) {
            log.warn("Timed out waiting for command execution to complete", e)
         }

      } catch (e: Exception) {
         handleK8sException(e, log)
      } finally {
         execWatch?.closeQuietly()
      }

      error.get()?.let { ex ->
         throw ContainerException("Error executing command: ${ex.message}", ex)
      }

      val exitCode = cmdExitCode.get().also {
         log.debug("Cmd execution exit-code: $it")
      }

      val stdErrString = stdErr.toUtf8String()

      return Pair(exitCode, stdErrString.ifBlank { null })
   }

   private fun createExecutionFutures(
      execWatch: ExecWatch,
      stdIn: InputStream?,
      stdOut: OutputStream,
      stdErr: ByteArrayOutputStream,
   ): Triple<CompletableFuture<Void>, CompletableFuture<Void>, CompletableFuture<Void>> {
      val processInputFuture = CompletableFuture.runAsync({
         processPodExecInput(execWatch, stdIn)
      }, AbstractContainerRuntime.GENERAL_EXECUTOR_SERVICE)

      val processOutputFuture = CompletableFuture.runAsync({
         processPodExecOutput(execWatch.output, stdOut)
      }, AbstractContainerRuntime.GENERAL_EXECUTOR_SERVICE)

      val processErrorFuture = CompletableFuture.runAsync({
         processPodExecOutput(execWatch.error, stdErr)
      }, AbstractContainerRuntime.GENERAL_EXECUTOR_SERVICE)

      return Triple(processInputFuture, processOutputFuture, processErrorFuture)
   }

   private fun waitForCompletion(
      futures: Triple<CompletableFuture<Void>, CompletableFuture<Void>, CompletableFuture<Void>>,
      timedOutputWait: Boolean = false,
      finishedLatch: CountDownLatch,
      waitTimeCalculator: WaitTimeCalculator?,
      waitTimeUnit: TimeUnit?,
   ) {
//      if (!timedOutputWait) {
      CompletableFuture.allOf(futures.first, futures.second, futures.third).let { allFutures ->
         waitTimeCalculator?.getRemainingTime()?.let { waitTimeRemaining ->
            log.debug("Waiting $waitTimeRemaining $waitTimeUnit for streams to complete")
            allFutures.get(waitTimeRemaining, waitTimeUnit!!)
         } ?: allFutures.join()
      }

      val remainingTime = waitTimeCalculator?.getRemainingTime()
      waitForLatch(finishedLatch, remainingTime, waitTimeUnit)

//      } else {
//         // DEFICIENCY ALERT!!!
//
//         // We cannot wait for the output and error futures to complete, as they will never complete before the execWatch is
//         // explicitly closed. We never get an onExit event, so we cannot wait for the finishedLatch to count down either.
//
//         // Bummer.
//
//         // Wait for the input future to complete
//         waitTimeCalculator?.getRemainingTime()?.let { waitTimeRemaining ->
//            futures.first.get(waitTimeRemaining, waitTimeUnit!!)
//         } ?: futures.first.join()
//
//         // Cheeky sleep to allow the input to be processed and output to be written - not good.
//         TimeUnit.SECONDS.sleep(3)
//      }
   }

   private fun processPodExecInput(execWatch: ExecWatch, input: InputStream?) {
      input?.use { cmdStdIn ->

         log.debug("Stdin data available for command execution: ${cmdStdIn.available()} bytes")

         execWatch.input?.use { cmdStdinAsOutputStream ->

            // ByteArrayOutputStream to capture the input for logging
            val logStream = ByteArrayOutputStream()

            // TeeOutputStream to write to both podStdin and logStream
            val teeStream = TeeOutputStream(cmdStdinAsOutputStream, logStream)

            // Transfer data from clientStdIn to teeStream, which writes to both cmdStdinAsOutputStream and logStream
            cmdStdIn.transferTo(teeStream)
            teeStream.flush()

            // Convert the captured input to a string and log it
            logStream.toUtf8String().also { debugString ->
               log.debug("Captured stdin data for command execution: ${debugString.truncate(1024)}")
            }

         } ?: log.warn("No pod stdin stream available for command execution")
      }
   }

   private fun processPodExecOutput(input: InputStream?, sink: OutputStream) {
      log.trace("Processing pod exec output...")

      sink.use {
         input?.use { containerOutputAsInputStream ->

            log.debug("Reading container output - available bytes: ${containerOutputAsInputStream.available()}")

            // ByteArrayOutputStream to capture the input for logging
            val logStream = ByteArrayOutputStream()

            // TeeOutputStream to write to both sink and logStream
            val teeStream = TeeOutputStream(sink, logStream)

            // Transfer data from podOut to teeStream, which writes to both sink and logStream
            containerOutputAsInputStream.transferTo(teeStream)

            // Convert the captured output to a string and log it
            logStream.toUtf8String().also { debugString ->
               log.debug("Captured output data for command execution: ${debugString.truncate(1024)}")
            }

         } ?: log.warn("The container output is not available for command execution")

         log.trace("Processing pod exec output finished")
      }
   }

   private fun waitForLatch(latch: CountDownLatch, waitTimeValue: Long?, waitTimeUnit: TimeUnit?) {
      if (waitTimeValue == null || waitTimeUnit == null) {
         log.trace("Waiting indefinitely for latch to count down")
         latch.await()
      } else {
         log.trace("Waiting $waitTimeValue $waitTimeUnit for latch to count down")
         val notified = latch.await(waitTimeValue, waitTimeUnit)
         if (!notified) {
            throw ContainerException("Timeout waiting $waitTimeValue $waitTimeUnit for latch to count down")
         } else {
            log.trace("Latch counted down!")
         }
      }
   }

   private fun executeCommand(
      pod: Pod,
      container: Container,
      command: List<String>,
      useTty: Boolean,
      input: InputStream?,
      redirectInput: Boolean,
      stdOut: OutputStream,
      redirectStdOut: Boolean,
      stdErr: OutputStream,
      redirectStdErr: Boolean,
      cmdExitCode: AtomicReference<Int>,
      error: AtomicReference<Throwable>,
      alive: AtomicBoolean,
      started: CountDownLatch,
      finished: CountDownLatch,
   ): ExecWatch {
      val listener = object : ExecListener {

         override fun onOpen() {
            log.trace("ExecListener: onOpen")
            alive.set(true)
            started.countDown()
         }

         override fun onFailure(throwable: Throwable?, response: ExecListener.Response?) {
            log.error("ExecListener: onFailure - msg '${throwable?.message}' - code '${response?.code()}", throwable)
            error.set(throwable)
            finished()
         }

         override fun onClose(code: Int, reason: String?) {
            log.debug("ExecListener: onClose: code=$code, reason=$reason")
            finished()
         }

         override fun onExit(code: Int, status: Status?) {
            log.debug("ExecListener: onExit: code=$code, status=${status?.status}")
            cmdExitCode.set(code)
            finished()
         }

         private fun finished() {
            alive.set(false)
            started.countDown()
            finished.countDown()
         }
      }

      return client
         .pods()
         .inNamespace(pod.metadata.namespace)
         .withName(pod.metadata.name)
         .inContainer(container.name)

         // Stdin,
         .let {
            if (input != null) {
               if (redirectInput) {
                  log.debug("Redirecting stdin")
                  it.redirectingInput()
               } else {
                  log.debug("Reading stdin ${input.available()} bytes available to command exec")
                  it.readingInput(input)
               }
            } else it
         }

         // Stdout
         .let {
            if (redirectStdOut) {
               log.debug("Redirecting stdout")
               it.redirectingOutput()
            } else {
               it.writingOutput(stdOut)
            }
         }

         // Stderr
         .let {
            if (redirectStdErr) {
               log.debug("Redirecting stderr")
               it.redirectingError()
            } else {
               it.writingError(stdErr)
            }
         }

         // TTY
         .let {
            if (useTty) {
               log.debug("Using TTY for command execution")
               it.withTTY()
            } else it
         }

         // Add listener
         .usingListener(listener)

         // Command
         .exec(*command.toTypedArray())
   }
}