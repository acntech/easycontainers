package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import no.acntech.easycontainers.kubernetes.K8sConstants.ENV_KUBERNETES_SERVICE_HOST
import no.acntech.easycontainers.kubernetes.K8sConstants.ENV_KUBERNETES_SERVICE_PORT
import no.acntech.easycontainers.kubernetes.K8sConstants.SERVICE_ACCOUNT_PATH
import no.acntech.easycontainers.util.text.FORWARD_SLASH
import no.acntech.easycontainers.util.text.HYPHEN
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Object representing utility functions for interacting with Kubernetes cluster.
 */
object K8sUtils {

   private val TOKEN_FILE = Paths.get("$SERVICE_ACCOUNT_PATH/token")

   private val CA_CERT_FILE = Paths.get("$SERVICE_ACCOUNT_PATH/ca.crt")

   private val LABEL_KEY_DNS_SUBDOMAIN_REGEX = "^[a-zA-Z0-9]([a-zA-Z0-9\\-.]{0,251}[a-zA-Z0-9])?$".toRegex()

   private val LABEL_REGEX = "^[a-zA-Z0-9]([a-zA-Z0-9\\-_\\.]{0,61}[a-zA-Z0-9])?$".toRegex()

   private val INVALID_LABEL_CHARS_REGEX = "[^a-zA-Z0-9\\-_\\.]".toRegex()

   private val MULTIPLE_HYPHENS_REGEX = "-+".toRegex()

   private val INSTANT_LABEL_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

   // Define maximum lengths
   private const val MAX_PREFIX_LENGTH = 253
   private const val MAX_LABEL_LENGTH = 63

   /**
    * Checks whether the current application is running inside a cluster.
    *
    * @return True if the application is running inside a cluster, False otherwise.
    */
   fun isRunningInsideCluster(): Boolean {
      return System.getenv(ENV_KUBERNETES_SERVICE_HOST) != null &&
         System.getenv(ENV_KUBERNETES_SERVICE_PORT) != null &&
         Files.exists(TOKEN_FILE) &&
         Files.exists(CA_CERT_FILE)
   }

   /**
    * Checks if the application is running outside of a cluster.
    *
    * @return `true` if the application is running outside of a cluster, `false` otherwise.
    */
   fun isRunningOutsideCluster(): Boolean {
      return !isRunningInsideCluster()
   }

   /**
    * Checks if the current user has access to the Cluster API.
    *
    * @return true if the user has access to the Cluster API, false otherwise.
    */
   fun canAccessClusterAPI(): Boolean {
      return try {
         KubernetesClientBuilder().build().use { client ->
            client.pods().list()
            true
         }
      } catch (e: KubernetesClientException) {
         false
      }
   }

   /**
    * Checks if the given label key is legal.
    */
   fun isLegalLabelKey(key: String): Boolean {
      val parts = key.split(FORWARD_SLASH)
      return when {
         parts.size > 2 -> false
         parts.size == 2 -> LABEL_KEY_DNS_SUBDOMAIN_REGEX.matches(parts[0]) && LABEL_REGEX.matches(parts[1])
         else -> LABEL_REGEX.matches(key)
      }
   }

   /**
    * Checks if the given value is a legal label value.
    *
    * @param value the value to be checked
    * @return true if the value is a legal label value, false otherwise
    */
   fun isLegalLabelValue(value: String): Boolean {
      return LABEL_REGEX.matches(value)
   }

   /**
    * Normalizes the given label key by separating the prefix and name if a forward slash is present,
    * normalizing the prefix and name by replacing invalid label characters with hyphens, removing
    * multiple consecutive hyphens, trimming the prefix and name to their respective maximum lengths,
    * and converting the prefix and name to lowercase. The normalized label key is then constructed
    * by concatenating the normalized prefix and name, separated by a forward slash if the prefix is not empty.
    *
    * @param labelKey The label key to be normalized.
    * @return The normalized label key.
    */
   fun normalizeLabelKey(labelKey: String): String {
      // Separate prefix and name if a slash is present
      val parts = labelKey.split(FORWARD_SLASH)
      val (prefix, name) = when {
         parts.size > 1 -> Pair(parts[0], parts[1])
         else -> Pair("", labelKey)
      }

      // Normalize prefix and name
      val normalizedPrefix = prefix.replace(INVALID_LABEL_CHARS_REGEX, "-")
         .replace(MULTIPLE_HYPHENS_REGEX, "-")
         .take(MAX_PREFIX_LENGTH)
         .trim('-')
         .lowercase()

      val normalizedName = name.replace(INVALID_LABEL_CHARS_REGEX, "-")
         .replace(MULTIPLE_HYPHENS_REGEX, "-")
         .take(MAX_LABEL_LENGTH)
         .trim('-')
         .lowercase()

      // Construct the normalized label key
      return if (normalizedPrefix.isNotEmpty()) "$normalizedPrefix/$normalizedName" else normalizedName
   }

   /**
    * Normalizes the given label value by replacing invalid characters, removing multiple hyphens,
    * trimming leading and trailing hyphens and underscores, and limiting the length to [MAX_LABEL_LENGTH].
    *
    * @param value The label value to normalize.
    * @return The normalized label value.
    */
   fun normalizeLabelValue(value: String): String {
      return value.replace(INVALID_LABEL_CHARS_REGEX, HYPHEN)
         .replace(MULTIPLE_HYPHENS_REGEX, HYPHEN)
         .take(MAX_LABEL_LENGTH)
         .trim { it == '-' || it == '_' }
   }

   /**
    * Converts an Instant to a legal label value.
    *
    * @param instant The Instant to convert to a label value.
    * @return The label value representation of the Instant.
    */
   fun instantToLabelValue(instant: Instant): String {
      return INSTANT_LABEL_FORMATTER.format(instant)
   }

}