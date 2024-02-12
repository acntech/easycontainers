package no.acntech.easycontainers.util.net

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * The NetworkUtils class provides utility methods related to network operations.
 */
object NetworkUtils {

   private val log = LoggerFactory.getLogger(NetworkUtils::class.java);

   private val IP4_ADDRESS_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

   /**
    * Checks if the given string represents a valid IPv4 address.
    *
    * @param ipAddress the string representing the IP address to be checked
    *
    * @return true if the given string is a valid IPv4 address, false otherwise
    */
   fun isIp4Address(ipAddress: String): Boolean {
      return ipAddress.matches(IP4_ADDRESS_REGEX)
   }

   /**
    * Retrieves the list of local IP addresses.
    *
    * This method loops through all the network interfaces of the system and collects the IP addresses
    * that are not loopback addresses and are site local addresses. The collected IP addresses are
    * added to a list and returned.
    *
    * @return A list of local IP addresses.
    *
    * @throws SocketException if an error occurs while retrieving the network interfaces.
    */
   fun getLocalIpAddresses(): List<String> {
      val ipAddresses = mutableListOf<String>()
      try {
         val networkInterfaces = NetworkInterface.getNetworkInterfaces()
         for (networkInterface in networkInterfaces) {
            if (networkInterface.isUp && !networkInterface.isLoopback) {
               val addresses = networkInterface.inetAddresses
               for (address in addresses) {
                  if (!address.isLoopbackAddress && address.isSiteLocalAddress) {
                     ipAddresses.add(address.hostAddress)
                  }
               }
            }
         }
      } catch (e: SocketException) {
         e.printStackTrace()
         println("Unable to determine local IP addresses")
      }
      return ipAddresses
   }

   fun isPortOpen(host: String, port: Int, timeoutMillis: Int = 5000): Boolean {
      return try {
         Socket().use { socket ->
            // Connects this socket to the server with a specified timeout value.
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMillis)
            true.also {
               log.debug("Port $port is OPEN on $host")
            } // The connection was successful, port is open
         }
      } catch (e: IOException) {
         false.also {
            log.debug("Port $port is CLOSED or NOT REACHABLE on $host")
         }
      }
   }

}