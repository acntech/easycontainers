package test.acntech.easycontainers.util.ssh.jcraft

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import no.acntech.easycontainers.util.text.EMPTY_STRING
import no.acntech.easycontainers.util.text.FORWARD_SLASH
import no.acntech.easycontainers.util.text.NEW_LINE
import test.acntech.easycontainers.util.ssh.SSHClient
import test.acntech.easycontainers.util.ssh.Session
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * An implementation of [SSHClient] that uses the JCraft library.
 */
internal class JSchSSHClient : SSHClient {

   private val jsch = JSch()

   private lateinit var jschSession: com.jcraft.jsch.Session

   companion object {
      private const val CHANNEL_TYPE_SFTP = "sftp"
      private const val CHANNEL_TYPE_EXEC = "exec"
   }

   override fun connect(host: String, port: Int, user: String, password: String): Session {
      jschSession = jsch.getSession(user, host, port).apply { setPassword(password) }
      return JSchSessionImpl()
   }

   override fun disconnect() {
      if(::jschSession.isInitialized && jschSession.isConnected) {
         jschSession.disconnect()
      }
   }

   inner class JSchSessionImpl() : Session {

      init {
         jschSession.setConfig("StrictHostKeyChecking", "no")
         jschSession.connect()
      }

      override fun runCommand(command: String): Triple<Int, String, String> {
         val channel = jschSession.openChannel(CHANNEL_TYPE_EXEC) as ChannelExec
         channel.setCommand(command)

         channel.inputStream = null
         val stdout = BufferedReader(InputStreamReader(channel.inputStream))
         val stderr = BufferedReader(InputStreamReader(channel.errStream))

         channel.connect()

         val output = generateSequence { stdout.readLine() }.joinToString(NEW_LINE)
         val error = generateSequence { stderr.readLine() }.joinToString(NEW_LINE)

         val exitStatus = channel.exitStatus

         channel.disconnect()

         return Triple(exitStatus, output, error)
      }

      override fun uploadFile(localPath: String, remotePath: String) {
         val sftpChannel = jschSession.openChannel(CHANNEL_TYPE_SFTP) as ChannelSftp
         sftpChannel.connect()
         try {
            // Get the remote directory from the remote path
            val remoteDir = remotePath.substringBeforeLast(FORWARD_SLASH)
            // Create the directory
            createRemoteDirectory(remoteDir)
            // Upload the file
            sftpChannel.put(localPath, remotePath)
         } finally {
            sftpChannel.exit()
         }
      }

      override fun downloadFile(remotePath: String, localPath: String) {
         val sftpChannel = jschSession.openChannel(CHANNEL_TYPE_SFTP) as ChannelSftp
         sftpChannel.connect()
         try {
            sftpChannel.get(remotePath, localPath)
         } finally {
            sftpChannel.exit()
         }
      }

      override fun createRemoteDirectory(remotePath: String) {
         val sftpChannel = jschSession.openChannel(CHANNEL_TYPE_SFTP) as ChannelSftp
         sftpChannel.connect()
         try {
            val directories = remotePath.split(FORWARD_SLASH)
            var currentDirectory = EMPTY_STRING
            for (dir in directories) {
               if (dir.isNotEmpty()) {
                  currentDirectory = "$currentDirectory/$dir"
                  try {
                     sftpChannel.lstat(currentDirectory)
                  } catch (e: com.jcraft.jsch.SftpException) {
                     // Directory does not exist.
                     sftpChannel.mkdir(currentDirectory)
                  }
               }
            }
         } finally {
            sftpChannel.exit()
         }
      }
   }

}