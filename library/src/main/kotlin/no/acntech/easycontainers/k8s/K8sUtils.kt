package no.acntech.easycontainers.k8s

import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import no.acntech.easycontainers.k8s.K8sConstants.ENV_KUBERNETES_SERVICE_HOST
import no.acntech.easycontainers.k8s.K8sConstants.ENV_KUBERNETES_SERVICE_PORT
import no.acntech.easycontainers.k8s.K8sConstants.SERVICE_ACCOUNT_PATH
import no.acntech.easycontainers.util.text.FORWARD_SLASH
import no.acntech.easycontainers.util.text.HYPHEN
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

   fun isRunningInsideCluster(): Boolean {
      return System.getenv(ENV_KUBERNETES_SERVICE_HOST) != null &&
         System.getenv(ENV_KUBERNETES_SERVICE_PORT) != null &&
         Files.exists(TOKEN_FILE) &&
         Files.exists(CA_CERT_FILE)
   }

   fun isRunningOutsideCluster(): Boolean {
      return !isRunningInsideCluster()
   }

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

   fun isLegalLabelKey(key: String): Boolean {
      val parts = key.split(FORWARD_SLASH)
      return when {
         parts.size > 2 -> false
         parts.size == 2 -> LABEL_KEY_DNS_SUBDOMAIN_REGEX.matches(parts[0]) && LABEL_REGEX.matches(parts[1])
         else -> LABEL_REGEX.matches(key)
      }
   }

   fun isLegalLabelValue(value: String): Boolean {
      return LABEL_REGEX.matches(value)
   }

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

   fun normalizeLabelValue(value: String): String {
      return value.replace(INVALID_LABEL_CHARS_REGEX, HYPHEN)
         .replace(MULTIPLE_HYPHENS_REGEX, HYPHEN)
         .take(MAX_LABEL_LENGTH)
         .trim { it == '-' || it == '_' }
   }

   fun instantToLabelValue(instant: Instant): String {
      return INSTANT_LABEL_FORMATTER.format(instant)
   }

}