package test.acntech.easycontainers.util.ssh

/**
 * A client for connecting to a remote host using SSH.
 */
interface SSHClient {

   /**
    * Connects to the remote host using the given credentials.
    * @param host the host to connect to
    * @param port the port to connect to
    * @param user the user to connect as
    * @param password the password to use
    * @return a session for executing commands on the remote host
    */
   fun connect(host: String, port: Int, user: String, password: String): Session

   /**
    * Disconnects from the remote host.
    */
   fun disconnect()

}