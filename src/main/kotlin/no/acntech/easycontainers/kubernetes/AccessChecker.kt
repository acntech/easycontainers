package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.authorization.v1.ResourceAttributesBuilder
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewSpec
import io.fabric8.kubernetes.client.KubernetesClient
import no.acntech.easycontainers.PermissionException
import no.acntech.easycontainers.kubernetes.K8sConstants.DEFAULT_NAMESPACE
import no.acntech.easycontainers.util.text.EMPTY_STRING
import org.slf4j.LoggerFactory

/**
 * AccessChecker is a class that provides methods to check access permissions for various operations
 * in a Kubernetes cluster.
 *
 * @property client - The Kubernetes client used for accessing the cluster.
 */
class AccessChecker(
   private val client: KubernetesClient,
) {

   companion object {
      private val log = LoggerFactory.getLogger(AccessChecker::class.java)

      // Verbs
      const val GET = "get"
      const val LIST = "list"
      const val CREATE = "create"
      const val DELETE = "delete"
      const val UPDATE = "update"
      const val WATCH = "watch"

      // Resources
      const val NAMESPACE = "namespaces"
      const val JOBS = "jobs"
      const val DEPLOYMENTS = "deployments"
      const val SERVICES = "services"
      const val PODS = "pods"
      const val CONFIG_MAPS = "configmaps"
      const val POD_LOGS = "pods/log"

      // Groups
      const val DEFAULT_GROUP = EMPTY_STRING
      const val BATCH_GROUP = "batch"
      const val APPS_GROUP = "apps"
   }

   fun canListNamespaces(): Boolean = checkPermission(false, DEFAULT_GROUP, NAMESPACE, LIST)

   fun requireListNamespaces() = canListNamespaces()

   fun canCreateNamespaces(): Boolean =
      checkPermission(resource = NAMESPACE, verbs = arrayOf(CREATE))

   fun requireCreateNamespaces() = checkPermission(true, resource = NAMESPACE, verbs = arrayOf(CREATE))

   fun canCreateDeployments(namespace: String = DEFAULT_NAMESPACE): Boolean =
      checkPermission(false, namespace, APPS_GROUP, DEPLOYMENTS, CREATE)

   fun requireCreateDeployments(namespace: String = DEFAULT_NAMESPACE) =
      checkPermission(true, namespace, APPS_GROUP, DEPLOYMENTS, CREATE)

   fun canMonitorDeployments(namespace: String = DEFAULT_NAMESPACE): Boolean =
      checkPermission(false, namespace, DEPLOYMENTS, APPS_GROUP, LIST) &&
         checkPermission(false, namespace, APPS_GROUP, DEPLOYMENTS, WATCH)

   fun requireMonitorDeployments(namespace: String = DEFAULT_NAMESPACE) =
      checkPermission(true, namespace, DEPLOYMENTS, APPS_GROUP, LIST) &&
         checkPermission(true, namespace, APPS_GROUP, DEPLOYMENTS, WATCH)

   fun canCreateServices(namespace: String = DEFAULT_NAMESPACE): Boolean =
      checkPermission(false, namespace, SERVICES, DEFAULT_GROUP, CREATE)

   fun requireCreateServices(namespace: String = DEFAULT_NAMESPACE) =
      checkPermission(true, namespace, SERVICES, DEFAULT_GROUP, CREATE)

   fun canCreateJobs(namespace: String = DEFAULT_NAMESPACE): Boolean =
      checkPermission(false, namespace, BATCH_GROUP, JOBS, CREATE)

   fun requireCreateJobs(namespace: String = DEFAULT_NAMESPACE) =
      checkPermission(true, namespace, BATCH_GROUP, JOBS, CREATE)

   fun canMonitorJobs(namespace: String = DEFAULT_NAMESPACE): Boolean =
      checkPermission(false, namespace, JOBS, BATCH_GROUP, LIST) &&
         checkPermission(false, namespace, BATCH_GROUP, JOBS, WATCH)

   fun requireMonitorJobs(namespace: String = DEFAULT_NAMESPACE) =
      checkPermission(true, namespace, JOBS, BATCH_GROUP, LIST) &&
         checkPermission(true, namespace, BATCH_GROUP, JOBS, WATCH)

   fun canListPods(namespace: String = DEFAULT_NAMESPACE): Boolean =
      checkPermission(false, namespace, DEFAULT_GROUP, PODS, LIST)

   fun requireListPods(namespace: String = DEFAULT_NAMESPACE) =
      checkPermission(true, namespace, DEFAULT_GROUP, PODS, LIST)

   fun canMonitorPods(namespace: String = DEFAULT_NAMESPACE): Boolean =
      checkPermission(false, namespace, DEFAULT_GROUP, PODS, LIST, WATCH) &&
         checkPermission(false, namespace, DEFAULT_GROUP, POD_LOGS, GET, WATCH)

   fun requireMonitorPods(namespace: String = DEFAULT_NAMESPACE) =
      checkPermission(true, namespace, DEFAULT_GROUP, PODS, LIST, WATCH) &&
         checkPermission(true, namespace, DEFAULT_GROUP, POD_LOGS, GET, WATCH)

   fun canCreateConfigMaps(namespace: String = DEFAULT_NAMESPACE): Boolean =
      checkPermission(false, namespace, DEFAULT_GROUP, CONFIG_MAPS, CREATE)

   fun requireCreateConfigMaps(namespace: String = DEFAULT_NAMESPACE) =
      checkPermission(true, namespace, DEFAULT_GROUP, CONFIG_MAPS, CREATE)

   private fun checkPermission(
      exceptionIfNotPermitted: Boolean = false,
      namespace: String = DEFAULT_NAMESPACE,
      group: String = DEFAULT_GROUP,
      resource: String,
      vararg verbs: String,
   ): Boolean {
      var allowed = true

      for (verb in verbs) {
         val accessReview = SelfSubjectAccessReview().apply {
            spec = SelfSubjectAccessReviewSpec().apply {
               resourceAttributes = ResourceAttributesBuilder()
                  .withGroup(group)
                  .withResource(resource)
                  .withVerb(verb).apply {
                     if (namespace.isNotBlank()) {
                        this.withNamespace(namespace)
                     }
                  }.build()
            }
         }

         val isAllowed = client.authorization().v1().selfSubjectAccessReview().create(accessReview).status.allowed

         if (!isAllowed) {
            log.warn("Access denied for verb [$verb] on resource [$resource] in namespace [$namespace] for API group [$group]")
            allowed = false
         }
      }

      if (!allowed && exceptionIfNotPermitted) {
         throw PermissionException(
            "Access denied for one or more verbs [$verbs] on " +
               "resource [$resource] in namespace [$namespace] for API group [$group]"
         )
      }

      return allowed
   }

}
