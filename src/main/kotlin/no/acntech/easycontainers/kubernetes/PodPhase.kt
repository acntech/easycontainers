package no.acntech.easycontainers.kubernetes

enum class PodPhase {
   PENDING,
   RUNNING,
   SUCCEEDED,
   FAILED,
   UNKNOWN,
   ;
}