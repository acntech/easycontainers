package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import no.acntech.easycontainers.util.lang.prettyPrintMe
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
      val actionName = action?.name ?: "UNKNOWN"
      val resourceInfo = resource?.prettyPrintMe() ?: "null"
      log.debug("LoggingWatcher: received event [$actionName] on resource: $resourceInfo")
   }

   override fun onClose(cause: WatcherException?) {
      if (cause != null) {
         log.debug("LoggingWatcher: closed due to an exception: ${cause.message}", cause)
      } else {
         log.debug("LoggingWatcher: closed without any exception")
      }
   }

}
