package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An implementation of the Watcher interface that logs events and exceptions.
 *
 * @param T the type of the watched resource
 * @property log the logger instance for logging events and exceptions
 */
internal class LoggingWatcher<Any>(
   val log: Logger = LoggerFactory.getLogger(LoggingWatcher::class.java),
) : Watcher<Any> {

   override fun eventReceived(action: Watcher.Action?, resource: Any?) {
      log.debug("LoggingWatcher: received event [${action?.name}] on [${getResourceInfo(resource)}]")
   }

   override fun onClose(cause: WatcherException?) {
      cause?.let {
         log.error("LoggingWatcher closed with exception", it)
      } ?: log.debug("LoggingWatcher closed")
   }

   private fun getResourceInfo(resource: Any?): String {
      return when (resource) {
         is HasMetadata -> {
            val metadata = resource.metadata
            "Kind: ${resource.kind}, Name: ${metadata.name}, Namespace: ${metadata.namespace}"
         }

         else -> resource?.toString() ?: "Unknown resource"
      }
   }

}
