package no.acntech.easycontainers.k8s

import no.acntech.easycontainers.util.text.EMPTY_STRING

object K8sConstants {
   const val APP_LABEL = "app"
   const val CLUSTER_IP_DIRECTIVE = "ClusterIP"
   const val NODE_PORT_DIRECTIVE = "NodePort"
   const val NODE_PORT_RANGE_START = 30000
   const val NODE_PORT_RANGE_END = 32767

   const val ENV_KUBERNETES_SERVICE_HOST = "KUBERNETES_SERVICE_HOST"
   const val ENV_KUBERNETES_SERVICE_PORT = "KUBERNETES_SERVICE_PORT"
   const val ENV_HOSTNAME = "HOSTNAME"

   const val SERVICE_ACCOUNT_PATH = "/var/run/secrets/kubernetes.io/serviceaccount"

   const val DEFAULT_NAMESPACE = "default"
}