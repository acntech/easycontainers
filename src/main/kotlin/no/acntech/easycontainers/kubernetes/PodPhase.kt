package no.acntech.easycontainers.kubernetes

/**
 * Represents the different phases of a pod in a Kubernetes cluster.
 */
enum class PodPhase {
   PENDING,
   RUNNING,
   SUCCEEDED,
   FAILED,
   UNKNOWN,
   ;
}