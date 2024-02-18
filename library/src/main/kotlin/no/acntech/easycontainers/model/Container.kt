package no.acntech.easycontainers.model

import no.acntech.easycontainers.ContainerType
import no.acntech.easycontainers.ExecutionMode
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Represents a container.
 *
 * This interface extends the [Runnable] interface, allowing the container to be executed as a separate thread, but note that
 * in general, the run() command doesn't wait for the container's completion.
 */
interface Container : Runnable {

   enum class State {
      CREATED,
      STARTED,
      RUNNING,
      FAILED,
      UNKNOWN,
      STOPPED,
      REMOVED
   }

   fun getState(): State

   fun getType(): ContainerType

   fun getExecutionMode(): ExecutionMode

   // Name

   /**
    * Returns the name of the container.
    *
    * @return the name of the container as a ContainerName object
    */
   fun getName(): ContainerName

   /**
    * Returns the namespace of the container.
    */
   fun getNamespace(): Namespace

   /**
    * Retrieves the labels associated with the container.
    *
    * @return a map of LabelKey to LabelValue representing the labels
    */
   fun getLabels(): Map<LabelKey, LabelValue>

   // Environment

   /**
    * Retrieves the environment variables associated with the container.
    *
    * @return a map containing the environment variable keys and values
    */
   fun getEnv(): Map<EnvVarKey, EnvVarValue>

   // Command

   /**
    * Retrieves the executable command for the container.
    * <p>
    * Note that setting a command (and/or args) will effectively neutralize the ENTRYPOINT directive in the container's
    * image for both Docker and kubernetes.
    *
    * @return the executable command as an Executable object, or null if no command is specified
    */
   fun getCommand(): Executable?

   /**
    * Retrieves the command arguments for the container.
    * <p>
    * Note that setting a command (and/or args) will effectively neutralize the ENTRYPOINT directive in the container's
    *
    * @return the command arguments as an instance of Args, or null if there are no arguments.
    */
   fun getArgs(): Args?

   // Image

   /**
    * Retrieves the image for the container.
    *
    * @return the URL of the image
    */
   fun getImage(): ImageURL

   // Ports

   /**
    * Returns the list of network ports exposed by the container.
    *
    * @return the list of exposed network ports as a List of NetworkPort objects
    */
   fun getExposedPorts(): List<NetworkPort>

   /**
    * Returns the mapped port for the specified network port.
    *
    * @param port the network port for which to retrieve the mapped port
    * @return the mapped port as a NetworkPort object
    */
   fun getMappedPort(port: NetworkPort): NetworkPort

   /**
    * Retrieves the port mappings for the container.
    *
    * @return a map of NetworkPort to NetworkPort representing the port mappings.
    */
   fun getPortMappings(): Map<NetworkPort, NetworkPort>

   /**
    * Checks if the given network port has a mapping defined in the container's port mappings.
    *
    * @param port The network port to check
    * @return true if the port has a mapping, false otherwise
    */
   fun hasPortMapping(port: NetworkPort): Boolean {
      return getPortMappings().containsKey(port)
   }

   /**
    * Checks whether the container is ephemeral.
    *
    * @return true if the container is ephemeral, false otherwise
    */

   fun isEphemeral(): Boolean

   /**
    * Retrieves the host of the container.
    *
    * @return the host of the container as a [Host] object, or null if the host is not set
    */

   fun getHost(): Host?

   fun getIpAddress(): InetAddress?

   /**
    * Retrieves the duration of the container execution (RUNNING state). If running, returns the time since the container
    * was started, if stopped, returns the time the container was running.
    */
   fun getDuration(): Duration?

   /**
    * Retrieves the exit code of the container.
    *
    * @return the exit code of the container, or null if the container is still running or has never been started.
    */
   fun getExitCode(): Int?

   /**
    * Executes a command in the container.
    * <p>
    * Note that for Docker, std out and std err are combined in the std out result - hence the std error string is always empty.
    *
    * @param executable the command to execute
    * @param args the arguments to pass to the command
    * @param workingDir the working directory for the command
    * @param input the input stream to pass to the command
    * @param waitTimeValue the time to wait for the command to complete
    * @param waitTimeUnit the time unit for the wait time
    * @return a Triple containing the exit code, the standard output and the standard error of the command execution
    */
   fun execute(
      executable: Executable,
      args: Args? = null,
      workingDir: UnixDir? = null,
      input: InputStream? = null,
      waitTimeValue: Long? = null,
      waitTimeUnit: TimeUnit? = null,
   ): Triple<Int, String, String>

   /**
    * Uploads a file to the container.
    *
    * @param localPath the path of the file to upload -
    * @param remoteDir the path where the file will be uploaded in the container - if it doesn't exist, it will be attempted created
    * @param remoteFilename the name of the file in the container - if null, the file will be uploaded with the same name as the
    * local file
    */
   fun putFile(localPath: Path, remoteDir: UnixDir, remoteFilename: String? = null)

   /**
    * Downloads a file from the container.
    *
    * @param remoteDir the path of the file to download
    * @param remoteFilename the name of the file in the container
    * @param localPath the path where the file will be downloaded to - if null, the file will be downloaded to the current
    *                  directory, with the same name as the remote file, if not null, the file will be downloaded with the
    *                  specified name unless the path is a directory, in which case the file will be downloaded to the directory
    *                  with the same name as the remote file
    * @return the path of the downloaded file
    */
   fun getFile(remoteDir: UnixDir, remoteFilename: String, localPath: Path? = null): Path

   /**
    * Uploads a directory to the container.
    *
    * @param localPath the path of the directory to upload
    * @param remoteDir the path where the directory will be uploaded in the container
    */
   fun putDirectory(localPath: Path, remoteDir: UnixDir)

   /**
    * Downloads a directory from the container.
    *
    * @param remoteDir the path of the directory to download
    * @param localPath the path where the directory will be downloaded to
    */
   fun getDirectory(remoteDir: UnixDir, localPath: Path)

   // Lifecycle

   fun waitForCompletion(timeoutValue: Long = Long.MAX_VALUE, timeoutUnit: TimeUnit = TimeUnit.DAYS): Int

   /**
    * Stops the container.
    *
    * This method stops the execution of the container. If the container is already stopped or removed, this method has no effect.
    */
   fun stop()

   /**
    * Stops the container.
    */
   fun kill() {
      stop()
   }

   /**
    * Removes the container.
    *
    * This method removes the container from the system. After calling this method, the container will no longer exist.
    */
   fun remove()

}