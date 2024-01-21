package no.acntech.easycontainers.util.net

import java.net.NetworkInterface
import java.net.SocketException

object NetworkUtils {

   private val IP4_ADDRESS_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

   fun isIp4Address(ipAddress: String): Boolean {
      return ipAddress.matches(IP4_ADDRESS_REGEX)
   }

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

}