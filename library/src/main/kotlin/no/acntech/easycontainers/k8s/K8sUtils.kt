package no.acntech.easycontainers.k8s

import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import no.acntech.easycontainers.k8s.K8sConstants.ENV_KUBERNETES_SERVICE_HOST
import no.acntech.easycontainers.k8s.K8sConstants.ENV_KUBERNETES_SERVICE_PORT
import no.acntech.easycontainers.k8s.K8sConstants.SERVICE_ACCOUNT_PATH
import java.nio.file.Files
import java.nio.file.Paths

object K8sUtils {

    private val TOKEN_FILE = Paths.get("$SERVICE_ACCOUNT_PATH/token")

    private val CA_CERT_FILE = Paths.get("$SERVICE_ACCOUNT_PATH/ca.crt")

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

}