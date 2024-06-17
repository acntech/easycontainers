package test.acntech.easycontainers.util.ssh

import test.acntech.easycontainers.util.ssh.jcraft.JSchSSHClient

object SSHClientFactory {

   /**
    * Creates a new SSH client.
    * @return a new SSH client
    */
   fun createDefaultClient(): SSHClient {
      return JSchSSHClient()
   }

}