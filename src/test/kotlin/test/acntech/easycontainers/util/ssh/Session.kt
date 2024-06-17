package test.acntech.easycontainers.util.ssh

interface Session {

   /**
    * Executes the given command on the remote host.
    * @param command the command to execute
    * @return a triple containing the exit code, the standard output and the standard error
    */
   fun runCommand(command: String): Triple<Int, String, String>

   /**
    * Uploads a file from the local file system to the remote file system.
    * @param localPath the path to the local file
    * @param remotePath the path to the remote file
    */
   fun uploadFile(localPath: String, remotePath: String)

   /**
    * Downloads a file from the remote file system to the local file system.
    * @param remotePath the path to the remote file
    * @param localPath the path to the local file
    */
   fun downloadFile(remotePath: String, localPath: String)

   /**
    * Creates a directory on the remote file system.
    * @param remotePath the path to the remote directory
    */
   fun createRemoteDirectory(remotePath: String)

}